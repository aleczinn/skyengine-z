package de.skyengine.client.network;

import de.skyengine.shared.network.Packet;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Client-owned replicated L0 cache. Completed batches become visible atomically. */
public final class ReplicatedChunkCache {
    public record ResyncRequest(String dimension, int chunkX, int chunkZ, long knownRevision) { }
    public record AppliedBatch(long batchId, long leaseId) { }
    public interface Listener {
        void chunkLoaded(ChunkColumnSnapshot chunk);
        /**
         * CPU-heavy canonical payload parsing hook. Render-world listeners run this on their
         * shared world pool so a burst of complete TCP fragments never stalls input/rendering.
         * The result must already use local runtime block-state IDs.
         */
        default CompletionStage<ChunkColumnSnapshot> decodeChunkPayloadAsync(
                byte[] payload, IntUnaryOperator blockStateMapper) throws ProtocolException {
            return CompletableFuture.completedFuture(
                    de.skyengine.shared.network.CoreProtocol.decodeChunkSnapshot(payload, blockStateMapper));
        }
        default CompletionStage<Void> chunkLoadedAsync(ChunkColumnSnapshot chunk) {
            chunkLoaded(chunk);
            return CompletableFuture.completedFuture(null);
        }
        /** Releases listener-owned preparation associated with an obsolete decoded snapshot. */
        default void discardDecodedChunk(ChunkColumnSnapshot chunk) { }
        void chunkUnloaded(String dimension, int chunkX, int chunkZ);
        void blocksChanged(String dimension, int chunkX, int chunkZ, long revision, List<BlockChange> changes);
        default ChunkColumnSnapshot snapshotAfterBlockChanges(ChunkColumnSnapshot previous,
                                                               long revision) { return null; }
        default void blockEntityChanged(String dimension, int chunkX, int chunkZ,
                                        de.skyengine.shared.world.BlockEntitySnapshot blockEntity) { }
    }

    private record Key(String dimension, long position) {}
    private static final class Batch {
        final String dimension;
        final long leaseId;
        final int expected;
        final long receiveOrder;
        final Map<Key, ChunkColumnSnapshot> chunks = new LinkedHashMap<>();
        FragmentAssembly fragments;
        int pendingPayloadDecodes;
        boolean ended;
        Batch(String dimension, long leaseId, int expected, long receiveOrder) {
            this.dimension = dimension;
            this.leaseId = leaseId;
            this.expected = expected;
            this.receiveOrder = receiveOrder;
        }
    }
    private static final class FragmentAssembly {
        final byte[][] parts;
        final int totalLength;
        int received;
        FragmentAssembly(int count, int totalLength) {
            this.parts = new byte[count][];
            this.totalLength = totalLength;
        }
    }
    private record PendingDelta(long revision, List<BlockChange> changes) { }
    private record BatchInstall(long batchId, long leaseId, long receiveOrder,
                                Map<Key, ChunkColumnSnapshot> chunks, Throwable failure) { }
    private record DecodedPayload(long batchId, ChunkColumnSnapshot snapshot, Throwable failure) { }
    private static final int MAX_PENDING_CHANGES_PER_CHUNK = 32_768;
    private static final int MAX_PENDING_BLOCK_ENTITIES_PER_CHUNK = 4_096;

    private final Map<Key, ChunkColumnSnapshot> chunks = new HashMap<>();
    private final Map<Key, Long> revisions = new HashMap<>();
    private final Map<Long, Batch> batches = new HashMap<>();
    private final Map<Key, List<PendingDelta>> pendingDeltas = new HashMap<>();
    private final Map<Key, List<de.skyengine.shared.world.BlockEntitySnapshot>> pendingBlockEntities =
            new HashMap<>();
    private final Map<Key, ChunkColumnSnapshot> pendingInstalls = new HashMap<>();
    private final Map<Key, Long> latestLeases = new HashMap<>();
    private final Map<Key, Long> installedLeases = new HashMap<>();
    private final Map<Key, Long> closedLeases = new HashMap<>();
    /* Logical receive order of the newest snapshot/unload for a coordinate. TCP preserves
       packet order, but payload decode and chunk preparation deliberately finish out of order.
       Keeping that order here prevents an old decode from resurrecting a column after its
       UnloadChunk (or from replacing a newer re-entry snapshot with the same world revision). */
    private final Map<Key, Long> latestCoordinateOrder = new HashMap<>();
    private final ConcurrentLinkedQueue<BatchInstall> completedInstalls = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DecodedPayload> completedPayloadDecodes = new ConcurrentLinkedQueue<>();
    private final java.util.ArrayDeque<AppliedBatch> completedBatchIds = new java.util.ArrayDeque<>();
    private Listener listener;
    private Consumer<ResyncRequest> resyncRequester = ignored -> { };
    private IntUnaryOperator blockStateMapper = IntUnaryOperator.identity();
    private long lastCompletedBatch;
    private long receiveOrder;

    public ReplicatedChunkCache(Listener listener) { this.listener = listener; }

    /** Attaches the render-world bridge after PLAY has created its GL-owned view. */
    public void setListener(Listener listener) { this.listener = listener; }
    public void setResyncRequester(Consumer<ResyncRequest> requester) {
        this.resyncRequester = requester == null ? ignored -> { } : requester;
    }

    /** Installs the negotiated server-network-ID to local-runtime-ID translation. */
    public void setBlockStateMapper(IntUnaryOperator mapper) {
        this.blockStateMapper = mapper == null ? IntUnaryOperator.identity() : mapper;
    }

    /** Stable snapshot used to seed a listener that was attached after initial chunk batches. */
    public List<ChunkColumnSnapshot> snapshots() { return List.copyOf(this.chunks.values()); }

    public void accept(Packet packet) throws ProtocolException {
        long packetOrder = ++this.receiveOrder;
        if (packet instanceof CorePackets.ChunkBatchStart start) {
            Key center = key(start.dimension(), start.centerChunkX(), start.centerChunkZ());
            this.latestLeases.merge(center, start.leaseId(), Math::max);
            if (this.batches.putIfAbsent(start.batchId(),
                    new Batch(start.dimension(), start.leaseId(), start.chunkCount(), packetOrder)) != null) {
                throw new ProtocolException("Duplicate chunk batch " + start.batchId());
            }
        } else if (packet instanceof CorePackets.ChunkColumnData data) {
            Batch batch = requiredBatch(data.batchId());
            ChunkColumnSnapshot chunk = remapBlockStates(data.chunk());
            if (!batch.dimension.equals(chunk.dimension())) throw new ProtocolException("Chunk batch dimension mismatch");
            Key key = key(chunk.dimension(), chunk.chunkX(), chunk.chunkZ());
            if (batch.chunks.putIfAbsent(key, chunk) != null) throw new ProtocolException("Duplicate chunk in batch");
            if (batch.chunks.size() > batch.expected) throw new ProtocolException("Chunk batch exceeds announced size");
        } else if (packet instanceof CorePackets.ChunkColumnFragment fragment) {
            acceptFragment(fragment);
        } else if (packet instanceof CorePackets.ChunkBatchEnd end) {
            Batch batch = requiredBatch(end.batchId());
            if (batch.ended) throw new ProtocolException("Duplicate chunk batch end");
            batch.ended = true;
            if (batch.pendingPayloadDecodes == 0) completeBatch(end.batchId(), batch);
        } else if (packet instanceof CorePackets.UnloadChunk unload) {
            Key key = key(unload.dimension(), unload.chunkX(), unload.chunkZ());
            this.closedLeases.merge(key, unload.leaseId(), Math::max);
            this.latestLeases.merge(key, unload.leaseId(), Math::max);
            this.latestCoordinateOrder.put(key, packetOrder);
            this.pendingInstalls.remove(key);
            this.pendingDeltas.remove(key);
            this.pendingBlockEntities.remove(key);
            long installedLease = this.installedLeases.getOrDefault(key, -1L);
            if (installedLease <= unload.leaseId()) {
                this.installedLeases.remove(key);
                this.revisions.remove(key);
            }
            if (installedLease <= unload.leaseId() && this.chunks.remove(key) != null
                    && this.listener != null) {
                this.listener.chunkUnloaded(unload.dimension(), unload.chunkX(), unload.chunkZ());
            }
        } else if (packet instanceof CorePackets.BlockUpdate update) {
            applyChanges(update.dimension(), update.chunkX(), update.chunkZ(), update.revision(), List.of(update.change()));
        } else if (packet instanceof CorePackets.MultiBlockUpdate update) {
            applyChanges(update.dimension(), update.chunkX(), update.chunkZ(), update.revision(), update.changes());
        } else if (packet instanceof CorePackets.BlockEntityUpdate update) {
            applyBlockEntityUpdate(update);
        }
    }

    /**
     * Commits prepared chunks on the client owner thread. The returned IDs may only then be
     * acknowledged, so the server never moves the collision frontier ahead of the client.
     */
    public List<AppliedBatch> drainCompletedBatchIds() throws ProtocolException {
        drainDecodedPayloads();
        BatchInstall install;
        while ((install = this.completedInstalls.poll()) != null) finishInstall(install);
        if (this.completedBatchIds.isEmpty()) return List.of();
        List<AppliedBatch> result = new ArrayList<>(this.completedBatchIds.size());
        while (!this.completedBatchIds.isEmpty()) result.add(this.completedBatchIds.removeFirst());
        return List.copyOf(result);
    }

    private void beginInstall(long batchId, Batch batch) throws ProtocolException {
        Map<Key, ChunkColumnSnapshot> accepted = new LinkedHashMap<>();
        List<CompletableFuture<Void>> preparations = new ArrayList<>();
        for (Map.Entry<Key, ChunkColumnSnapshot> entry : batch.chunks.entrySet()) {
            if (batch.leaseId < this.latestLeases.getOrDefault(entry.getKey(), batch.leaseId)
                    || batch.leaseId <= this.closedLeases.getOrDefault(entry.getKey(), -1L)) {
                discardDecoded(entry.getValue());
                continue;
            }
            long newestOrder = this.latestCoordinateOrder.getOrDefault(entry.getKey(), Long.MIN_VALUE);
            if (batch.receiveOrder < newestOrder) {
                discardDecoded(entry.getValue());
                continue;
            }
            ChunkColumnSnapshot old = this.chunks.get(entry.getKey());
            ChunkColumnSnapshot pending = this.pendingInstalls.get(entry.getKey());
            long knownRevision = Math.max(old == null ? -1L : old.revision(),
                    pending == null ? -1L : pending.revision());
            if (entry.getValue().revision() < knownRevision) {
                discardDecoded(entry.getValue());
                continue;
            }
            this.latestCoordinateOrder.put(entry.getKey(), batch.receiveOrder);
            accepted.put(entry.getKey(), entry.getValue());
            this.pendingInstalls.put(entry.getKey(), entry.getValue());
            if (this.listener != null) {
                try {
                    preparations.add(this.listener.chunkLoadedAsync(entry.getValue()).toCompletableFuture());
                } catch (RuntimeException failure) {
                    throw new ProtocolException("Chunk preparation failed", failure);
                }
            }
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(
                preparations.toArray(CompletableFuture[]::new));
        if (all.isDone()) {
            Throwable failure = null;
            try { all.join(); }
            catch (CompletionException exception) { failure = exception.getCause(); }
            finishInstall(new BatchInstall(batchId, batch.leaseId, batch.receiveOrder,
                    Map.copyOf(accepted), failure));
        } else {
            all.whenComplete((ignored, failure) -> this.completedInstalls.add(
                    new BatchInstall(batchId, batch.leaseId, batch.receiveOrder,
                            Map.copyOf(accepted), failure)));
        }
    }

    private void finishInstall(BatchInstall install) throws ProtocolException {
        if (install.failure() != null) {
            throw new ProtocolException("Chunk preparation failed", install.failure());
        }
        for (Map.Entry<Key, ChunkColumnSnapshot> entry : install.chunks().entrySet()) {
            if (install.leaseId() < this.latestLeases.getOrDefault(entry.getKey(), install.leaseId())
                    || install.leaseId() <= this.closedLeases.getOrDefault(entry.getKey(), -1L)) continue;
            if (this.latestCoordinateOrder.getOrDefault(entry.getKey(), Long.MIN_VALUE)
                    != install.receiveOrder()) continue;
            if (this.pendingInstalls.get(entry.getKey()) != entry.getValue()) continue;
            this.pendingInstalls.remove(entry.getKey());
            this.chunks.put(entry.getKey(), entry.getValue());
            this.installedLeases.put(entry.getKey(), install.leaseId());
            this.revisions.put(entry.getKey(), entry.getValue().revision());
            applyPending(entry.getKey());
        }
        this.lastCompletedBatch = Math.max(this.lastCompletedBatch, install.batchId());
        this.completedBatchIds.addLast(new AppliedBatch(install.batchId(), install.leaseId()));
    }

    private void acceptFragment(CorePackets.ChunkColumnFragment fragment) throws ProtocolException {
        Batch batch = requiredBatch(fragment.batchId());
        FragmentAssembly assembly = batch.fragments;
        if (assembly == null) {
            assembly = new FragmentAssembly(fragment.fragmentCount(), fragment.totalLength());
            batch.fragments = assembly;
        } else if (assembly.parts.length != fragment.fragmentCount()
                || assembly.totalLength != fragment.totalLength()) {
            throw new ProtocolException("Inconsistent chunk fragment header");
        }
        if (assembly.parts[fragment.fragmentIndex()] != null) {
            throw new ProtocolException("Duplicate chunk fragment");
        }
        assembly.parts[fragment.fragmentIndex()] = fragment.data();
        assembly.received++;
        if (assembly.received != assembly.parts.length) return;
        byte[] payload = new byte[assembly.totalLength];
        int offset = 0;
        for (byte[] part : assembly.parts) {
            if (offset + part.length > payload.length) throw new ProtocolException("Chunk fragments exceed length");
            System.arraycopy(part, 0, payload, offset, part.length);
            offset += part.length;
        }
        if (offset != payload.length) throw new ProtocolException("Incomplete chunk fragment payload");
        batch.fragments = null;
        batch.pendingPayloadDecodes++;
        CompletionStage<ChunkColumnSnapshot> decode;
        try {
            decode = this.listener == null
                    ? CompletableFuture.completedFuture(
                            de.skyengine.shared.network.CoreProtocol.decodeChunkSnapshot(
                                    payload, this.blockStateMapper))
                    : this.listener.decodeChunkPayloadAsync(payload, this.blockStateMapper);
        } catch (Throwable failure) {
            batch.pendingPayloadDecodes--;
            throw failure instanceof ProtocolException protocol ? protocol
                    : new ProtocolException("Chunk payload decode failed", failure);
        }
        decode.whenComplete((snapshot, failure) -> this.completedPayloadDecodes.add(
                new DecodedPayload(fragment.batchId(), snapshot, unwrapCompletionFailure(failure))));
    }

    private void drainDecodedPayloads() throws ProtocolException {
        DecodedPayload decoded;
        while ((decoded = this.completedPayloadDecodes.poll()) != null) {
            Batch batch = this.batches.get(decoded.batchId());
            if (batch == null) {
                if (decoded.snapshot() != null) discardDecoded(decoded.snapshot());
                continue; // disconnected/replaced cache; obsolete worker result
            }
            if (decoded.failure() != null) {
                this.batches.remove(decoded.batchId());
                throw new ProtocolException("Chunk payload decode failed", decoded.failure());
            }
            ChunkColumnSnapshot chunk = decoded.snapshot();
            if (chunk == null || !batch.dimension.equals(chunk.dimension())) {
                if (chunk != null) discardDecoded(chunk);
                throw new ProtocolException("Chunk batch dimension mismatch");
            }
            Key key = key(chunk.dimension(), chunk.chunkX(), chunk.chunkZ());
            if (batch.chunks.putIfAbsent(key, chunk) != null) {
                discardDecoded(chunk);
                throw new ProtocolException("Duplicate chunk in batch");
            }
            if (batch.chunks.size() > batch.expected) {
                batch.chunks.remove(key, chunk);
                discardDecoded(chunk);
                throw new ProtocolException("Chunk batch exceeds announced size");
            }
            batch.pendingPayloadDecodes--;
            if (batch.ended && batch.pendingPayloadDecodes == 0) completeBatch(decoded.batchId(), batch);
        }
    }

    private void completeBatch(long batchId, Batch batch) throws ProtocolException {
        if (batch.chunks.size() != batch.expected) throw new ProtocolException("Incomplete chunk batch");
        if (!this.batches.remove(batchId, batch)) return;
        beginInstall(batchId, batch);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    private void discardDecoded(ChunkColumnSnapshot snapshot) {
        if (this.listener != null) this.listener.discardDecodedChunk(snapshot);
    }

    private void applyBlockEntityUpdate(CorePackets.BlockEntityUpdate update) {
        Key key = key(update.dimension(), update.chunkX(), update.chunkZ());
        ChunkColumnSnapshot current = this.chunks.get(key);
        if (current == null) {
            List<de.skyengine.shared.world.BlockEntitySnapshot> pending =
                    this.pendingBlockEntities.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (pending.size() >= MAX_PENDING_BLOCK_ENTITIES_PER_CHUNK) {
                this.pendingBlockEntities.remove(key);
                this.resyncRequester.accept(new ResyncRequest(update.dimension(), update.chunkX(),
                        update.chunkZ(), this.revisions.getOrDefault(key, 0L)));
                return;
            }
            pending.add(update.blockEntity());
            return;
        }
        applyBlockEntity(key, current, update.blockEntity());
    }

    private void applyBlockEntity(Key key, ChunkColumnSnapshot current,
                                  de.skyengine.shared.world.BlockEntitySnapshot changed) {
        List<de.skyengine.shared.world.BlockEntitySnapshot> blockEntities =
                new ArrayList<>(current.blockEntities());
        blockEntities.removeIf(existing -> existing.localX() == changed.localX()
                && existing.y() == changed.y() && existing.localZ() == changed.localZ());
        blockEntities.add(changed);
        this.chunks.put(key, new ChunkColumnSnapshot(current.dimension(), current.chunkX(), current.chunkZ(),
                current.revision(), current.sections(), current.biomeIds(), current.grassTintCorners(),
                current.foliageTintCorners(), current.heightmap(), blockEntities));
        if (this.listener != null) this.listener.blockEntityChanged(current.dimension(), current.chunkX(),
                current.chunkZ(), changed);
    }

    public ChunkColumnSnapshot get(String dimension, int chunkX, int chunkZ) {
        return this.chunks.get(key(dimension, chunkX, chunkZ));
    }
    public ResyncRequest resyncRequest(String dimension, int chunkX, int chunkZ) {
        Key key = key(dimension, chunkX, chunkZ);
        ChunkColumnSnapshot chunk = this.chunks.get(key);
        return new ResyncRequest(dimension, chunkX, chunkZ,
                this.revisions.getOrDefault(key, chunk == null ? 0L : chunk.revision()));
    }
    public void requestResyncNow(String dimension, int chunkX, int chunkZ) {
        this.resyncRequester.accept(resyncRequest(dimension, chunkX, chunkZ));
    }
    public int size() { return this.chunks.size(); }
    public long lastCompletedBatch() { return this.lastCompletedBatch; }

    private void applyChanges(String dimension, int chunkX, int chunkZ, long revision,
                              List<BlockChange> changes) throws ProtocolException {
        ChunkColumnSnapshot chunk = get(dimension, chunkX, chunkZ);
        Key key = key(dimension, chunkX, chunkZ);
        if (chunk == null) {
            List<PendingDelta> pending = this.pendingDeltas.computeIfAbsent(key, ignored -> new ArrayList<>());
            int changeCount = changes.size();
            for (PendingDelta delta : pending) changeCount += delta.changes().size();
            if (changeCount > MAX_PENDING_CHANGES_PER_CHUNK) {
                this.pendingDeltas.remove(key);
                this.resyncRequester.accept(new ResyncRequest(dimension, chunkX, chunkZ,
                        this.revisions.getOrDefault(key, 0L)));
                return;
            }
            pending.add(new PendingDelta(revision, List.copyOf(changes)));
            return;
        }
        long currentRevision = this.revisions.getOrDefault(key, chunk.revision());
        if (revision <= currentRevision) return;
        // A single authoritative simulation transaction may mutate a chunk several times
        // (pistons, fluids, redstone). The packet contains the coalesced final values and its
        // chunk epoch therefore legitimately jumps by more than one.
        List<BlockChange> remapped = new ArrayList<>(changes.size());
        try {
            for (BlockChange change : changes) {
                remapped.add(new BlockChange(change.localX(), change.y(), change.localZ(),
                        this.blockStateMapper.applyAsInt(change.stateId())));
            }
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException(invalid.getMessage() == null
                    ? "Invalid block-state mapping" : invalid.getMessage());
        }
        this.revisions.put(key, revision);
        if (this.listener != null) this.listener.blocksChanged(dimension, chunkX, chunkZ, revision,
                List.copyOf(remapped));
        ChunkColumnSnapshot replacement = this.listener == null ? null
                : this.listener.snapshotAfterBlockChanges(chunk, revision);
        if (replacement == null) {
            replacement = new ChunkColumnSnapshot(chunk.dimension(), chunk.chunkX(), chunk.chunkZ(),
                    revision, chunk.sections(), chunk.biomeIds(), chunk.grassTintCorners(),
                    chunk.foliageTintCorners(), chunk.heightmap(), chunk.blockEntities());
        }
        this.chunks.put(key, replacement);
    }

    private void applyPending(Key key) throws ProtocolException {
        List<PendingDelta> deltas = this.pendingDeltas.remove(key);
        if (deltas != null) {
            deltas.sort(java.util.Comparator.comparingLong(PendingDelta::revision));
            ChunkColumnSnapshot current = this.chunks.get(key);
            for (PendingDelta delta : deltas) {
                applyChanges(current.dimension(), current.chunkX(), current.chunkZ(),
                        delta.revision(), delta.changes());
                current = this.chunks.get(key);
            }
        }
        List<de.skyengine.shared.world.BlockEntitySnapshot> blockEntities =
                this.pendingBlockEntities.remove(key);
        if (blockEntities != null) {
            for (var blockEntity : blockEntities) {
                ChunkColumnSnapshot current = this.chunks.get(key);
                if (current != null) applyBlockEntity(key, current, blockEntity);
            }
        }
    }

    private Batch requiredBatch(long id) throws ProtocolException {
        Batch batch = this.batches.get(id);
        if (batch == null) throw new ProtocolException("Unknown chunk batch " + id);
        return batch;
    }

    private static Key key(String dimension, int chunkX, int chunkZ) {
        return new Key(dimension, ChunkPosition.pack(chunkX, chunkZ));
    }

    private ChunkColumnSnapshot remapBlockStates(ChunkColumnSnapshot source) throws ProtocolException {
        return remapBlockStates(source, this.blockStateMapper);
    }

    static ChunkColumnSnapshot remapBlockStates(ChunkColumnSnapshot source,
                                                IntUnaryOperator mapper) throws ProtocolException {
        List<de.skyengine.shared.world.ChunkSectionSnapshot> sections = new ArrayList<>(source.sections().size());
        try {
            for (de.skyengine.shared.world.ChunkSectionSnapshot section : source.sections()) {
                int[] palette = section.palette();
                for (int i = 0; i < palette.length; i++) palette[i] = mapper.applyAsInt(palette[i]);
                sections.add(new de.skyengine.shared.world.ChunkSectionSnapshot(section.sectionY(),
                        section.nonAir(), palette, section.bitsPerEntry(), section.packedPaletteIndices(),
                        section.skyLight(), section.blockLight()));
            }
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException(invalid.getMessage() == null
                    ? "Invalid block-state mapping" : invalid.getMessage());
        }
        return new ChunkColumnSnapshot(source.dimension(), source.chunkX(), source.chunkZ(), source.revision(),
                sections, source.biomeIds(), source.grassTintCorners(), source.foliageTintCorners(),
                source.heightmap(), source.blockEntities());
    }
}
