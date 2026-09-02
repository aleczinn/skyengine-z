package de.skyengine.client.network;

import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.BlockEntitySnapshot;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplicatedChunkCacheTest {
    @Test
    void completedBatchPublishesAtomicallyAndCoalescedRevisionJumpsAreAccepted() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        ReplicatedChunkCache cache = new ReplicatedChunkCache(new ReplicatedChunkCache.Listener() {
            @Override public void chunkLoaded(ChunkColumnSnapshot chunk) { loads.incrementAndGet(); }
            @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) { }
            @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                                List<BlockChange> changes) { updates.incrementAndGet(); }
        });
        ChunkColumnSnapshot chunk = emptyChunk(4);
        cache.accept(new CorePackets.ChunkBatchStart(7, chunk.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(7, chunk));
        assertNull(cache.get(chunk.dimension(), 0, 0));
        cache.accept(new CorePackets.ChunkBatchEnd(7));
        assertNotNull(cache.get(chunk.dimension(), 0, 0));
        assertEquals(1, loads.get());
        assertEquals(7, cache.lastCompletedBatch());

        cache.accept(new CorePackets.BlockUpdate(chunk.dimension(), 0, 0, 5,
                new BlockChange(1, 2, 3, 42)));
        assertEquals(1, updates.get());
        cache.accept(new CorePackets.BlockUpdate(chunk.dimension(), 0, 0, 7,
                new BlockChange(1, 2, 3, 43)));
        assertEquals(2, updates.get());
    }

    @Test
    void incompleteAndDuplicateBatchesAreRejected() throws Exception {
        ReplicatedChunkCache cache = new ReplicatedChunkCache(null);
        cache.accept(new CorePackets.ChunkBatchStart(1, "skyengine:overworld", 0, 0, 1));
        assertThrows(ProtocolException.class, () -> cache.accept(new CorePackets.ChunkBatchStart(
                1, "skyengine:overworld", 0, 0, 1)));
        assertThrows(ProtocolException.class, () -> cache.accept(new CorePackets.ChunkBatchEnd(1)));
    }

    @Test
    void fragmentedChunkRemainsInvisibleUntilEveryFragmentAndBatchEndArrive() throws Exception {
        ReplicatedChunkCache cache = new ReplicatedChunkCache(null);
        ChunkColumnSnapshot chunk = emptyChunk(12);
        byte[] encoded = de.skyengine.shared.network.CoreProtocol.encodeChunkSnapshot(chunk);
        int split = Math.max(1, encoded.length / 2);
        byte[] first = java.util.Arrays.copyOfRange(encoded, 0, split);
        byte[] second = java.util.Arrays.copyOfRange(encoded, split, encoded.length);

        cache.accept(new CorePackets.ChunkBatchStart(12, chunk.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnFragment(12, 0, 2, encoded.length, first));
        assertNull(cache.get(chunk.dimension(), 0, 0));
        cache.accept(new CorePackets.ChunkColumnFragment(12, 1, 2, encoded.length, second));
        assertNull(cache.get(chunk.dimension(), 0, 0));
        cache.accept(new CorePackets.ChunkBatchEnd(12));

        assertNotNull(cache.get(chunk.dimension(), 0, 0));
        assertEquals(12, cache.get(chunk.dimension(), 0, 0).revision());
    }

    @Test
    void blockEntityUpdateReplacesSnapshotWithoutAdvancingBlockRevision() throws Exception {
        AtomicInteger updates = new AtomicInteger();
        ReplicatedChunkCache cache = new ReplicatedChunkCache(new ReplicatedChunkCache.Listener() {
            @Override public void chunkLoaded(ChunkColumnSnapshot chunk) { }
            @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) { }
            @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                                List<BlockChange> changes) { }
            @Override public void blockEntityChanged(String dimension, int chunkX, int chunkZ,
                                                     BlockEntitySnapshot blockEntity) {
                updates.incrementAndGet();
            }
        });
        ChunkColumnSnapshot chunk = emptyChunk(4);
        cache.accept(new CorePackets.ChunkBatchStart(9, chunk.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(9, chunk));
        cache.accept(new CorePackets.ChunkBatchEnd(9));

        BlockEntitySnapshot first = new BlockEntitySnapshot(1, 64, 2,
                "skyengine:chest", new byte[]{1});
        BlockEntitySnapshot second = new BlockEntitySnapshot(1, 64, 2,
                "skyengine:chest", new byte[]{2});
        cache.accept(new CorePackets.BlockEntityUpdate(chunk.dimension(), 0, 0, first));
        cache.accept(new CorePackets.BlockEntityUpdate(chunk.dimension(), 0, 0, second));

        assertEquals(2, updates.get());
        assertEquals(1, cache.get(chunk.dimension(), 0, 0).blockEntities().size());
        assertEquals(2, cache.get(chunk.dimension(), 0, 0).blockEntities().getFirst().dataView()[0]);
        // Revision 5 must still be accepted: BE animation/data packets do not consume a block epoch.
        cache.accept(new CorePackets.BlockUpdate(chunk.dimension(), 0, 0, 5,
                new BlockChange(1, 2, 3, 42)));
    }

    @Test
    void updateThatOvertakesInitialSnapshotIsAppliedAfterAtomicPublication() throws Exception {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger lastState = new AtomicInteger();
        ReplicatedChunkCache cache = new ReplicatedChunkCache(new ReplicatedChunkCache.Listener() {
            @Override public void chunkLoaded(ChunkColumnSnapshot chunk) { }
            @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) { }
            @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                                List<BlockChange> changes) {
                updates.incrementAndGet();
                lastState.set(changes.getFirst().stateId());
            }
        });
        ChunkColumnSnapshot chunk = emptyChunk(4);

        cache.accept(new CorePackets.BlockUpdate(chunk.dimension(), 0, 0, 6,
                new BlockChange(2, 70, 3, 91)));
        assertNull(cache.get(chunk.dimension(), 0, 0));

        cache.accept(new CorePackets.ChunkBatchStart(19, chunk.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(19, chunk));
        cache.accept(new CorePackets.ChunkBatchEnd(19));

        assertEquals(1, updates.get());
        assertEquals(91, lastState.get());
        assertEquals(6, cache.get(chunk.dimension(), 0, 0).revision());
    }

    private static ChunkColumnSnapshot emptyChunk(long revision) {
        return new ChunkColumnSnapshot("skyengine:overworld", 0, 0, revision, List.of(),
                new int[ChunkColumnSnapshot.COLUMN_CELLS], new int[ChunkColumnSnapshot.TINT_CORNERS],
                new int[ChunkColumnSnapshot.TINT_CORNERS], new int[ChunkColumnSnapshot.COLUMN_CELLS]);
    }
}
