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

/** Client-owned replicated L0 cache. Completed batches become visible atomically. */
public final class ReplicatedChunkCache {
    public interface Listener {
        void chunkLoaded(ChunkColumnSnapshot chunk);
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
        final int expected;
        final Map<Key, ChunkColumnSnapshot> chunks = new LinkedHashMap<>();
        Batch(String dimension, int expected) { this.dimension = dimension; this.expected = expected; }
    }

    private final Map<Key, ChunkColumnSnapshot> chunks = new HashMap<>();
    private final Map<Key, Long> revisions = new HashMap<>();
    private final Map<Long, Batch> batches = new HashMap<>();
    private Listener listener;
    private IntUnaryOperator blockStateMapper = IntUnaryOperator.identity();
    private long lastCompletedBatch;

    public ReplicatedChunkCache(Listener listener) { this.listener = listener; }

    /** Attaches the render-world bridge after PLAY has created its GL-owned view. */
    public void setListener(Listener listener) { this.listener = listener; }

    /** Installs the negotiated server-network-ID to local-runtime-ID translation. */
    public void setBlockStateMapper(IntUnaryOperator mapper) {
        this.blockStateMapper = mapper == null ? IntUnaryOperator.identity() : mapper;
    }

    /** Stable snapshot used to seed a listener that was attached after initial chunk batches. */
    public List<ChunkColumnSnapshot> snapshots() { return List.copyOf(this.chunks.values()); }

    public void accept(Packet packet) throws ProtocolException {
        if (packet instanceof CorePackets.ChunkBatchStart start) {
            if (this.batches.putIfAbsent(start.batchId(), new Batch(start.dimension(), start.chunkCount())) != null) {
                throw new ProtocolException("Duplicate chunk batch " + start.batchId());
            }
        } else if (packet instanceof CorePackets.ChunkColumnData data) {
            Batch batch = requiredBatch(data.batchId());
            ChunkColumnSnapshot chunk = remapBlockStates(data.chunk());
            if (!batch.dimension.equals(chunk.dimension())) throw new ProtocolException("Chunk batch dimension mismatch");
            Key key = key(chunk.dimension(), chunk.chunkX(), chunk.chunkZ());
            if (batch.chunks.putIfAbsent(key, chunk) != null) throw new ProtocolException("Duplicate chunk in batch");
            if (batch.chunks.size() > batch.expected) throw new ProtocolException("Chunk batch exceeds announced size");
        } else if (packet instanceof CorePackets.ChunkBatchEnd end) {
            Batch batch = requiredBatch(end.batchId());
            if (batch.chunks.size() != batch.expected) throw new ProtocolException("Incomplete chunk batch");
            this.batches.remove(end.batchId());
            for (Map.Entry<Key, ChunkColumnSnapshot> entry : batch.chunks.entrySet()) {
                ChunkColumnSnapshot old = this.chunks.get(entry.getKey());
                if (old == null || entry.getValue().revision() >= old.revision()) {
                    this.chunks.put(entry.getKey(), entry.getValue());
                    this.revisions.put(entry.getKey(), entry.getValue().revision());
                    if (this.listener != null) this.listener.chunkLoaded(entry.getValue());
                }
            }
            this.lastCompletedBatch = Math.max(this.lastCompletedBatch, end.batchId());
        } else if (packet instanceof CorePackets.UnloadChunk unload) {
            Key key = key(unload.dimension(), unload.chunkX(), unload.chunkZ());
            this.revisions.remove(key);
            if (this.chunks.remove(key) != null
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

    private void applyBlockEntityUpdate(CorePackets.BlockEntityUpdate update) {
        Key key = key(update.dimension(), update.chunkX(), update.chunkZ());
        ChunkColumnSnapshot current = this.chunks.get(key);
        if (current == null) return;
        var changed = update.blockEntity();
        List<de.skyengine.shared.world.BlockEntitySnapshot> blockEntities =
                new ArrayList<>(current.blockEntities());
        blockEntities.removeIf(existing -> existing.localX() == changed.localX()
                && existing.y() == changed.y() && existing.localZ() == changed.localZ());
        blockEntities.add(changed);
        this.chunks.put(key, new ChunkColumnSnapshot(current.dimension(), current.chunkX(), current.chunkZ(),
                current.revision(), current.sections(), current.biomeIds(), current.grassTintCorners(),
                current.foliageTintCorners(), current.heightmap(), blockEntities));
        if (this.listener != null) this.listener.blockEntityChanged(update.dimension(), update.chunkX(),
                update.chunkZ(), changed);
    }

    public ChunkColumnSnapshot get(String dimension, int chunkX, int chunkZ) {
        return this.chunks.get(key(dimension, chunkX, chunkZ));
    }
    public int size() { return this.chunks.size(); }
    public long lastCompletedBatch() { return this.lastCompletedBatch; }

    private void applyChanges(String dimension, int chunkX, int chunkZ, long revision,
                              List<BlockChange> changes) throws ProtocolException {
        ChunkColumnSnapshot chunk = get(dimension, chunkX, chunkZ);
        if (chunk == null) return;
        Key key = key(dimension, chunkX, chunkZ);
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

    private Batch requiredBatch(long id) throws ProtocolException {
        Batch batch = this.batches.get(id);
        if (batch == null) throw new ProtocolException("Unknown chunk batch " + id);
        return batch;
    }

    private static Key key(String dimension, int chunkX, int chunkZ) {
        return new Key(dimension, ChunkPosition.pack(chunkX, chunkZ));
    }

    private ChunkColumnSnapshot remapBlockStates(ChunkColumnSnapshot source) throws ProtocolException {
        List<de.skyengine.shared.world.ChunkSectionSnapshot> sections = new ArrayList<>(source.sections().size());
        try {
            for (de.skyengine.shared.world.ChunkSectionSnapshot section : source.sections()) {
                int[] palette = section.palette();
                for (int i = 0; i < palette.length; i++) palette[i] = this.blockStateMapper.applyAsInt(palette[i]);
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
