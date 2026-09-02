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

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Owner-thread bridge from network L0 snapshots to the existing chunk mesher/renderer input. */
public final class ReplicatedChunkWorldAdapter implements ReplicatedChunkCache.Listener {
    private final String dimension;
    private final ChunkManager chunks;
    private final Dimension world;
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

    @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) {
        if (this.dimension.equals(dimension)) this.chunks.removeReplicatedChunk(chunkX, chunkZ);
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
        this.authoritativeUpdateListener.accept(chunkX, chunkZ);
    }

    @Override public ChunkColumnSnapshot snapshotAfterBlockChanges(ChunkColumnSnapshot previous,
                                                                   long revision) {
        Chunk chunk = this.chunks.getChunk(previous.chunkX(), previous.chunkZ());
        return chunk == null ? null
                : LegacyChunkSnapshotEncoder.encodeReplicated(previous, chunk, revision);
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
