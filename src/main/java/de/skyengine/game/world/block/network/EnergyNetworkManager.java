package de.skyengine.game.world.block.network;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.entity.EnergyStorage;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.graphics.PerformanceProfiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dimension-local RF graph. Cables are stateless blocks: they are walked only while a topology
 * rebuild is required. Stable ticks visit cached machine endpoints only.
 */
public final class EnergyNetworkManager {

    public static final long BASIC_NETWORK_THROUGHPUT = 3_200L;
    private static final String ENERGY_CONNECTION_GROUP = "energy";

    private final Dimension world;
    private final List<Network> networks = new ArrayList<>();
    private boolean dirty = true;
    private int lastSimulationChunkX = Integer.MIN_VALUE;
    private int lastSimulationChunkZ = Integer.MIN_VALUE;
    private long transferredThisTick;
    private long topologyRebuilds;
    private int endpointCount;
    private volatile List<FlowCable> flowCables = List.of();

    public EnergyNetworkManager(Dimension world) {
        this.world = world;
    }

    public void invalidate() {
        this.dirty = true;
    }

    public static boolean isConnector(BlockState state) {
        return state != null && ENERGY_CONNECTION_GROUP.equals(state.getBlock().getConnectionGroup());
    }

    public void tick(int simulationChunkX, int simulationChunkZ) {
        if (simulationChunkX != this.lastSimulationChunkX || simulationChunkZ != this.lastSimulationChunkZ) {
            this.lastSimulationChunkX = simulationChunkX;
            this.lastSimulationChunkZ = simulationChunkZ;
            this.dirty = true;
        }
        if (this.dirty) rebuild();

        this.transferredThisTick = 0;
        Map<Long, Long> receivedByMachine = new HashMap<>();
        Map<Long, Long> extractedByMachine = new HashMap<>();
        for (Network network : this.networks) {
            long moved = network.transfer(receivedByMachine, extractedByMachine);
            this.transferredThisTick += moved;
            network.activity = moved > 0 ? 1F : Math.max(0F, network.activity - 1F / 6F);
        }
        List<FlowCable> visible = new ArrayList<>();
        for (Network network : this.networks) if (network.activity > 0)
            for (Cable cable : network.cables) visible.add(new FlowCable(cable.pos, cable.mask, network.activity));
        this.flowCables = List.copyOf(visible);
        PerformanceProfiler profiler = PerformanceProfiler.get();
        profiler.set(PerformanceProfiler.Counter.ACTIVE_ENERGY_NETWORKS, this.networks.size());
        profiler.set(PerformanceProfiler.Counter.ENERGY_ENDPOINTS, this.endpointCount);
        profiler.set(PerformanceProfiler.Counter.ENERGY_TRANSFERRED, this.transferredThisTick);
        profiler.set(PerformanceProfiler.Counter.ENERGY_TOPOLOGY_REBUILDS, this.topologyRebuilds);
    }

    public int networkCount() { return this.networks.size(); }
    public long transferredThisTick() { return this.transferredThisTick; }
    public long topologyRebuilds() { return this.topologyRebuilds; }
    public List<FlowCable> flowCables() { return this.flowCables; }

    public record FlowCable(BlockPos pos, int connectionMask, float intensity) {}

    private void rebuild() {
        this.dirty = false;
        this.topologyRebuilds++;
        this.networks.clear();
        this.endpointCount = 0;

        List<BlockEntity> machines = new ArrayList<>();
        for (Chunk chunk : this.world.getChunkManager().chunksWithBlockEntities()) {
            if (chunk.status != ChunkStatus.READY
                    || !this.world.isPositionSimulated(chunk.chunkX << 5, chunk.chunkZ << 5)) continue;
            for (BlockEntity blockEntity : chunk.blockEntities()) {
                if (hasEnergySide(blockEntity)) machines.add(blockEntity);
            }
        }
        machines.sort(Comparator.comparingLong(be -> BlockPos.asLong(
                be.getPos().x(), be.getPos().y(), be.getPos().z())));

        Map<Long, Endpoint> endpointByFace = new LinkedHashMap<>();
        for (BlockEntity machine : machines) collectEndpoints(machine, endpointByFace);

        Set<Long> visitedCables = new HashSet<>();
        Set<DirectKey> visitedDirect = new HashSet<>();
        for (Endpoint endpoint : endpointByFace.values()) {
            BlockPos neighbor = endpoint.pos.offset(endpoint.side);
            if (!this.world.isPositionSimulated(neighbor.x(), neighbor.z())) continue;
            BlockState neighborState = Blocks.getState(this.world.getBlock(neighbor.x(), neighbor.y(), neighbor.z()));
            if (isConnector(neighborState)) {
                long cableKey = BlockPos.asLong(neighbor.x(), neighbor.y(), neighbor.z());
                if (!visitedCables.contains(cableKey)) {
                    this.networks.add(buildCableNetwork(neighbor, visitedCables, endpointByFace));
                }
                continue;
            }

            BlockEntity other = this.world.getBlockEntity(neighbor.x(), neighbor.y(), neighbor.z());
            if (other == null) continue;
            long otherFaceKey = faceKey(neighbor, endpoint.side.opposite());
            Endpoint otherEndpoint = endpointByFace.get(otherFaceKey);
            if (otherEndpoint == null) continue;
            DirectKey key = DirectKey.of(endpoint, otherEndpoint);
            if (visitedDirect.add(key)) this.networks.add(new Network(List.of(endpoint, otherEndpoint), List.of(), true));
        }
        this.networks.removeIf(network -> network.endpoints.size() < 2);
        this.endpointCount = endpointByFace.size();
    }

    private Network buildCableNetwork(BlockPos start, Set<Long> visitedCables,
                                      Map<Long, Endpoint> endpointByFace) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LinkedHashMap<Long, Endpoint> endpoints = new LinkedHashMap<>();
        List<BlockPos> positions = new ArrayList<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos cable = queue.removeFirst();
            long key = BlockPos.asLong(cable.x(), cable.y(), cable.z());
            if (!visitedCables.add(key)) continue;
            positions.add(cable);
            for (Direction direction : Direction.sharedValues()) {
                BlockPos neighbor = cable.offset(direction);
                if (!this.world.isPositionSimulated(neighbor.x(), neighbor.z())) continue;
                BlockState state = Blocks.getState(this.world.getBlock(neighbor.x(), neighbor.y(), neighbor.z()));
                if (isConnector(state)) {
                    long neighborKey = BlockPos.asLong(neighbor.x(), neighbor.y(), neighbor.z());
                    if (!visitedCables.contains(neighborKey)) queue.addLast(neighbor);
                } else {
                    Endpoint endpoint = endpointByFace.get(faceKey(neighbor, direction.opposite()));
                    if (endpoint != null) endpoints.put(endpoint.key(), endpoint);
                }
            }
        }
        List<Cable> cables = new ArrayList<>(positions.size());
        for (BlockPos cable : positions) {
            int mask = 0;
            for (Direction direction : Direction.sharedValues()) {
                BlockPos neighbor = cable.offset(direction);
                BlockState state = Blocks.getState(this.world.getBlock(neighbor.x(), neighbor.y(), neighbor.z()));
                if (isConnector(state) || endpointByFace.containsKey(faceKey(neighbor, direction.opposite())))
                    mask |= 1 << direction.ordinal();
            }
            cables.add(new Cable(cable, mask));
        }
        return new Network(new ArrayList<>(endpoints.values()), cables, false);
    }

    private static boolean hasEnergySide(BlockEntity blockEntity) {
        for (Direction direction : Direction.sharedValues()) {
            if (blockEntity.getCapability(Capabilities.ENERGY, direction).isPresent()) return true;
        }
        return false;
    }

    private static void collectEndpoints(BlockEntity blockEntity, Map<Long, Endpoint> endpoints) {
        for (Direction side : Direction.sharedValues()) {
            EnergyStorage storage = blockEntity.getCapability(Capabilities.ENERGY, side).orElse(null);
            if (storage == null || (!storage.canReceive() && !storage.canExtract())) continue;
            Endpoint endpoint = new Endpoint(blockEntity.getPos(), side, storage, blockEntity);
            endpoints.put(endpoint.key(), endpoint);
        }
    }

    private static long faceKey(BlockPos pos, Direction side) {
        return (BlockPos.asLong(pos.x(), pos.y(), pos.z()) * 7L) + side.ordinal() + 1L;
    }

    private record Endpoint(BlockPos pos, Direction side, EnergyStorage storage, BlockEntity owner) {
        long ownerKey() { return BlockPos.asLong(pos.x(), pos.y(), pos.z()); }
        long key() { return faceKey(pos, side); }
    }

    private record DirectKey(long first, long second) {
        static DirectKey of(Endpoint a, Endpoint b) {
            long ak = a.key(), bk = b.key();
            return ak < bk ? new DirectKey(ak, bk) : new DirectKey(bk, ak);
        }
    }

    private record Cable(BlockPos pos, int mask) {}

    private static final class Network {
        private final List<Endpoint> endpoints;
        private final boolean direct;
        private final List<Cable> cables;
        private float activity;
        private int sourceCursor;
        private int sinkCursor;

        private Network(List<Endpoint> endpoints, List<Cable> cables, boolean direct) {
            this.endpoints = endpoints;
            this.cables = cables;
            this.direct = direct;
        }

        long transfer(Map<Long, Long> receivedByMachine, Map<Long, Long> extractedByMachine) {
            if (this.endpoints.size() < 2) return 0;
            long networkBudget = BASIC_NETWORK_THROUGHPUT;
            long movedTotal = 0;
            int count = this.endpoints.size();
            for (int si = 0; si < count && networkBudget > 0; si++) {
                Endpoint source = this.endpoints.get((this.sourceCursor + si) % count);
                if (!source.storage.canExtract()) continue;
                if (this.direct && source.owner instanceof DirectEnergyOutput policy
                        && !policy.allowsDirectEnergyOutput(source.side)) continue;
                long ownerExtracted = extractedByMachine.getOrDefault(source.ownerKey(), 0L);
                long sourceBudget = Math.max(0, source.storage.getMaxExtract() - ownerExtracted);
                long available = source.storage.extract(Math.min(networkBudget, sourceBudget), true);
                if (available <= 0) continue;

                for (int ti = 0; ti < count && available > 0 && networkBudget > 0; ti++) {
                    Endpoint sink = this.endpoints.get((this.sinkCursor + ti) % count);
                    if (sink.ownerKey() == source.ownerKey() || !sink.storage.canReceive()) continue;
                    long ownerReceived = receivedByMachine.getOrDefault(sink.ownerKey(), 0L);
                    long sinkBudget = Math.max(0, sink.storage.getMaxReceive() - ownerReceived);
                    long offered = Math.min(Math.min(available, networkBudget), sinkBudget);
                    long accepted = sink.storage.receive(offered, true);
                    long extracted = source.storage.extract(accepted, false);
                    long inserted = sink.storage.receive(extracted, false);
                    if (inserted != extracted) {
                        // Capability contracts require simulation and execution to agree.
                        source.storage.receive(extracted - inserted, false);
                    }
                    if (inserted <= 0) continue;
                    available -= inserted;
                    networkBudget -= inserted;
                    movedTotal += inserted;
                    extractedByMachine.merge(source.ownerKey(), inserted, Long::sum);
                    receivedByMachine.merge(sink.ownerKey(), inserted, Long::sum);
                }
            }
            this.sourceCursor = (this.sourceCursor + 1) % count;
            this.sinkCursor = (this.sinkCursor + 1) % count;
            return movedTotal;
        }
    }
}
