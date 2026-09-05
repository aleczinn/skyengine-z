package de.skyengine.client.network;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.graphics.PerformanceProfiler;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

/** Owner-thread bridge from network L0 snapshots to the existing chunk mesher/renderer input. */
public final class ReplicatedChunkWorldAdapter implements ReplicatedChunkCache.Listener {
    private record Prepared(ChunkColumnSnapshot snapshot, Chunk chunk, boolean visible, Throwable failure,
                            CompletableFuture<Void> completion) { }
    private final String dimension;
    private final ChunkManager chunks;
    private final Dimension world;
    private final ConcurrentLinkedQueue<Prepared> prepared = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, ChunkColumnSnapshot> latestPreparations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkColumnSnapshot, Chunk> payloadDecodedChunks = new ConcurrentHashMap<>();
    private BiConsumer<Integer, Integer> authoritativeUpdateListener = (x, z) -> { };

    public ReplicatedChunkWorldAdapter(String dimension, ChunkManager chunks) {
        this(dimension, chunks, null);
    }

    public ReplicatedChunkWorldAdapter(String dimension, ChunkManager chunks, Dimension world) {
        this.dimension = Objects.requireNonNull(dimension);
        this.chunks = Objects.requireNonNull(chunks);
        this.world = world;
    }

    public ChunkManager chunkManager() { return this.chunks; }
    public void setAuthoritativeUpdateListener(BiConsumer<Integer, Integer> listener) {
        this.authoritativeUpdateListener = listener == null ? (x, z) -> { } : listener;
    }

    @Override public void chunkLoaded(ChunkColumnSnapshot snapshot) {
        if (!this.dimension.equals(snapshot.dimension())) return;
        try {
            this.chunks.installReplicatedChunk(LegacyChunkSnapshotDecoder.decode(snapshot, this.world));
            this.authoritativeUpdateListener.accept(snapshot.chunkX(), snapshot.chunkZ());
        } catch (ProtocolException invalidSnapshot) {
            throw new IllegalArgumentException("Invalid replicated chunk " + snapshot.chunkX()
                    + "," + snapshot.chunkZ(), invalidSnapshot);
        }
    }

    @Override
    public CompletionStage<Void> chunkLoadedAsync(ChunkColumnSnapshot snapshot) {
        return chunkLoadedAsync(snapshot, true);
    }

    @Override
    public CompletionStage<Void> chunkLoadedAsync(ChunkColumnSnapshot snapshot, boolean visible) {
        if (!this.dimension.equals(snapshot.dimension())) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        long key = Chunk.key(snapshot.chunkX(), snapshot.chunkZ());
        this.latestPreparations.put(key, snapshot);
        Chunk payloadDecoded = this.payloadDecodedChunks.remove(snapshot);
        if (payloadDecoded != null) {
            this.prepared.add(new Prepared(snapshot, payloadDecoded, visible, null, completion));
            return completion;
        }
        this.chunks.prepareReplicatedChunk(() -> {
            long started = PerformanceProfiler.get().begin();
            try {
                Chunk decoded = LegacyChunkSnapshotDecoder.decode(snapshot, this.world);
                this.prepared.add(new Prepared(snapshot, decoded, visible, null, completion));
            } catch (Throwable failure) {
                this.prepared.add(new Prepared(snapshot, null, visible, failure, completion));
            } finally {
                PerformanceProfiler.get().recordElapsed(
                        PerformanceProfiler.WorkerSection.L0_REMOTE_DECODE, started);
            }
        });
        return completion;
    }

    @Override
    public CompletionStage<ChunkColumnSnapshot> decodeChunkPayloadAsync(
            byte[] payload, java.util.function.IntUnaryOperator blockStateMapper) {
        CompletableFuture<ChunkColumnSnapshot> completion = new CompletableFuture<>();
        this.chunks.prepareReplicatedChunk(() -> {
            long started = PerformanceProfiler.get().begin();
            try {
                ChunkColumnSnapshot snapshot = de.skyengine.shared.network.CoreProtocol
                        .decodeChunkSnapshot(payload, blockStateMapper);
                Chunk decoded = LegacyChunkSnapshotDecoder.decode(snapshot, this.world);
                this.payloadDecodedChunks.put(snapshot, decoded);
                completion.complete(snapshot);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            } finally {
                PerformanceProfiler.get().recordElapsed(
                        PerformanceProfiler.WorkerSection.L0_REMOTE_DECODE, started);
            }
        });
        return completion;
    }

    @Override
    public void discardDecodedChunk(ChunkColumnSnapshot snapshot) {
        this.payloadDecodedChunks.remove(snapshot);
        this.latestPreparations.remove(Chunk.key(snapshot.chunkX(), snapshot.chunkZ()), snapshot);
    }

    /** Uebernimmt fertige CPU-Decodes ausschliesslich auf dem Client-Owner-Thread. */
    public void drainPreparedChunks() {
        Prepared result;
        while ((result = this.prepared.poll()) != null) {
            if (result.failure() != null) {
                result.completion().completeExceptionally(result.failure());
                continue;
            }
            long key = Chunk.key(result.snapshot().chunkX(), result.snapshot().chunkZ());
            if (this.latestPreparations.get(key) == result.snapshot()) {
                long started = PerformanceProfiler.get().begin();
                this.latestPreparations.remove(key, result.snapshot());
                this.chunks.installReplicatedChunk(result.chunk(), result.visible());
                this.authoritativeUpdateListener.accept(
                        result.snapshot().chunkX(), result.snapshot().chunkZ());
                PerformanceProfiler.get().recordElapsed(
                        PerformanceProfiler.WorkerSection.L0_CLIENT_INSTALL, started);
            }
            result.completion().complete(null);
        }
    }

    @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) {
        if (this.dimension.equals(dimension)) {
            this.latestPreparations.remove(Chunk.key(chunkX, chunkZ));
            this.chunks.removeReplicatedChunk(chunkX, chunkZ);
        }
    }

    @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                        List<BlockChange> changes) {
        if (!this.dimension.equals(dimension)) return;
        Chunk chunk = this.chunks.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return;

        for (BlockChange change : changes) {
            if (this.world != null) {
                this.world.applyReplicatedBlockState(
                        (chunkX << ChunkSection.SHIFT) + change.localX(), change.y(),
                        (chunkZ << ChunkSection.SHIFT) + change.localZ(), change.stateId());
            } else {
                chunk.writeLock().lock();
                try {
                    int oldState = chunk.getBlock(change.localX(), change.y(), change.localZ());
                    chunk.setBlock(change.localX(), change.y(), change.localZ(), change.stateId());
                    this.updateBlockEntity(chunk, change, oldState);
                } finally {
                    chunk.writeLock().unlock();
                }
            }
        }
        if (this.world == null) {
            for (BlockChange change : changes) markGeometryDirty(chunkX, chunkZ, change);
        }
    }

    @Override
    public void chunkVisibilityChanged(String dimension, int chunkX, int chunkZ, boolean visible) {
        if (this.dimension.equals(dimension)) {
            this.chunks.setReplicatedChunkVisible(chunkX, chunkZ, visible);
        }
    }

    @Override public ChunkColumnSnapshot snapshotAfterBlockChanges(ChunkColumnSnapshot previous,
                                                                   long revision,
                                                                   List<BlockChange> changes) {
        /* The mutable render/collision chunk may contain optimistic local edits. Advance the
           confirmed basis only from its previous immutable revision and the server delta. */
        de.skyengine.graphics.PerformanceProfiler profiler =
                de.skyengine.graphics.PerformanceProfiler.get();
        long started = profiler.begin();
        ChunkColumnSnapshot confirmed;
        try {
            confirmed = LegacyChunkSnapshotEncoder.applyConfirmedBlockChanges(
                    previous, revision, changes);
        } finally {
            profiler.recordElapsed(
                    de.skyengine.graphics.PerformanceProfiler.WorkerSection.L0_CLIENT_DELTA_COW,
                    started);
        }
        profiler.add(de.skyengine.graphics.PerformanceProfiler.Counter.L0_CLIENT_DELTA_COW_BYTES,
                confirmed.newlyAllocatedBytesComparedTo(previous));
        this.authoritativeUpdateListener.accept(previous.chunkX(), previous.chunkZ());
        return confirmed;
    }

    @Override public void blockEntityChanged(String dimension, int chunkX, int chunkZ,
                                             de.skyengine.shared.world.BlockEntitySnapshot blockEntity) {
        if (!this.dimension.equals(dimension)) return;
        Chunk chunk = this.chunks.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return;
        chunk.writeLock().lock();
        try {
            LegacyChunkSnapshotDecoder.installBlockEntity(chunk, blockEntity, this.world);
        } catch (ProtocolException invalid) {
            throw new IllegalArgumentException("Invalid replicated block entity", invalid);
        } finally {
            chunk.writeLock().unlock();
        }
    }

    private void updateBlockEntity(Chunk chunk, BlockChange change, int oldStateId) {
        var oldType = Blocks.getState(oldStateId).getBlock().getBlockEntityType();
        var newState = Blocks.getState(change.stateId());
        var newType = newState.getBlock().getBlockEntityType();
        if (oldType == newType) return;
        if (oldType != null) chunk.removeBlockEntity(change.localX(), change.y(), change.localZ());
        if (newType == null) return;
        BlockPos pos = new BlockPos((chunk.chunkX << ChunkSection.SHIFT) + change.localX(),
                change.y(), (chunk.chunkZ << ChunkSection.SHIFT) + change.localZ());
        var entity = newType.create(pos, newState);
        if (this.world != null) entity.setWorld(this.world);
        chunk.setBlockEntity(change.localX(), change.y(), change.localZ(), entity);
    }

    private void markGeometryDirty(int chunkX, int chunkZ, BlockChange change) {
        int sectionY = change.y() >> ChunkSection.SHIFT;
        markColumn(chunkX, chunkZ, sectionY, change.y());
        int x = change.localX(), z = change.localZ();
        if (x == 0) markColumn(chunkX - 1, chunkZ, sectionY, change.y());
        if (x == ChunkSection.MASK) markColumn(chunkX + 1, chunkZ, sectionY, change.y());
        if (z == 0) markColumn(chunkX, chunkZ - 1, sectionY, change.y());
        if (z == ChunkSection.MASK) markColumn(chunkX, chunkZ + 1, sectionY, change.y());
        if (x == 0 && z == 0) markColumn(chunkX - 1, chunkZ - 1, sectionY, change.y());
        if (x == 0 && z == ChunkSection.MASK) markColumn(chunkX - 1, chunkZ + 1, sectionY, change.y());
        if (x == ChunkSection.MASK && z == 0) markColumn(chunkX + 1, chunkZ - 1, sectionY, change.y());
        if (x == ChunkSection.MASK && z == ChunkSection.MASK) {
            markColumn(chunkX + 1, chunkZ + 1, sectionY, change.y());
        }
    }

    private void markColumn(int chunkX, int chunkZ, int sectionY, int y) {
        Chunk chunk = this.chunks.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return;
        chunk.markSectionDirty(sectionY);
        int localY = y & ChunkSection.MASK;
        if (localY == 0 && sectionY > 0) chunk.markSectionDirty(sectionY - 1);
        if (localY == ChunkSection.MASK && sectionY < Chunk.SECTIONS - 1) {
            chunk.markSectionDirty(sectionY + 1);
        }
    }
}
