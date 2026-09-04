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
        cache.accept(new CorePackets.ChunkBatchStart(7, 70, chunk.dimension(), 0, 0, 1));
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
        cache.accept(new CorePackets.ChunkBatchStart(1, 10, "skyengine:overworld", 0, 0, 1));
        assertThrows(ProtocolException.class, () -> cache.accept(new CorePackets.ChunkBatchStart(
                1, 10, "skyengine:overworld", 0, 0, 1)));
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

        cache.accept(new CorePackets.ChunkBatchStart(12, 120, chunk.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnFragment(12, 0, 2, encoded.length, first));
        assertNull(cache.get(chunk.dimension(), 0, 0));
        cache.accept(new CorePackets.ChunkColumnFragment(12, 1, 2, encoded.length, second));
        assertNull(cache.get(chunk.dimension(), 0, 0));
        cache.accept(new CorePackets.ChunkBatchEnd(12));
        cache.drainCompletedBatchIds();

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
        cache.accept(new CorePackets.ChunkBatchStart(9, 90, chunk.dimension(), 0, 0, 1));
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

        cache.accept(new CorePackets.ChunkBatchStart(19, 190, chunk.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(19, chunk));
        cache.accept(new CorePackets.ChunkBatchEnd(19));

        assertEquals(1, updates.get());
        assertEquals(91, lastState.get());
        assertEquals(6, cache.get(chunk.dimension(), 0, 0).revision());
    }

    @Test
    void asynchronousPreparationDelaysPublicationAndBatchAcknowledgement() throws Exception {
        java.util.concurrent.CompletableFuture<Void> prepared = new java.util.concurrent.CompletableFuture<>();
        AtomicInteger updates = new AtomicInteger();
        ReplicatedChunkCache cache = new ReplicatedChunkCache(new ReplicatedChunkCache.Listener() {
            @Override public void chunkLoaded(ChunkColumnSnapshot chunk) { }
            @Override public java.util.concurrent.CompletionStage<Void> chunkLoadedAsync(
                    ChunkColumnSnapshot chunk) { return prepared; }
            @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) { }
            @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                                List<BlockChange> changes) { updates.incrementAndGet(); }
        });
        ChunkColumnSnapshot chunk = emptyChunk(1);
        cache.accept(new CorePackets.ChunkBatchStart(23, 230, chunk.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(23, chunk));
        cache.accept(new CorePackets.ChunkBatchEnd(23));
        cache.accept(new CorePackets.BlockUpdate(chunk.dimension(), 0, 0, 2,
                new BlockChange(1, 2, 3, 42)));

        assertNull(cache.get(chunk.dimension(), 0, 0));
        assertEquals(List.of(), cache.drainCompletedBatchIds());
        assertEquals(0, updates.get());

        prepared.complete(null);
        assertEquals(List.of(new ReplicatedChunkCache.AppliedBatch(23, 230)),
                cache.drainCompletedBatchIds());
        assertEquals(2, cache.get(chunk.dimension(), 0, 0).revision());
        assertEquals(1, updates.get());
    }

    @Test
    void unloadWinsAgainstOlderAsynchronousPreparationAndAllowsLaterReentry() throws Exception {
        java.util.concurrent.CompletableFuture<Void> firstPreparation = new java.util.concurrent.CompletableFuture<>();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger preparations = new AtomicInteger();
        ReplicatedChunkCache cache = new ReplicatedChunkCache(new ReplicatedChunkCache.Listener() {
            @Override public void chunkLoaded(ChunkColumnSnapshot chunk) { loads.incrementAndGet(); }
            @Override public java.util.concurrent.CompletionStage<Void> chunkLoadedAsync(
                    ChunkColumnSnapshot chunk) {
                return preparations.getAndIncrement() == 0
                        ? firstPreparation : completedLoad(chunk);
            }
            private java.util.concurrent.CompletionStage<Void> completedLoad(ChunkColumnSnapshot chunk) {
                chunkLoaded(chunk);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) { }
            @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                                List<BlockChange> changes) { }
        });
        ChunkColumnSnapshot old = emptyChunk(1);
        cache.accept(new CorePackets.ChunkBatchStart(30, 300, old.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(30, old));
        cache.accept(new CorePackets.ChunkBatchEnd(30));
        cache.accept(new CorePackets.UnloadChunk(300, old.dimension(), 0, 0));

        firstPreparation.complete(null);
        assertEquals(List.of(new ReplicatedChunkCache.AppliedBatch(30, 300)),
                cache.drainCompletedBatchIds());
        assertNull(cache.get(old.dimension(), 0, 0));
        assertEquals(0, loads.get());

        // An unchanged server chunk legitimately has the same world revision after re-entry.
        ChunkColumnSnapshot reentered = emptyChunk(1);
        cache.accept(new CorePackets.ChunkBatchStart(31, 301, reentered.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(31, reentered));
        cache.accept(new CorePackets.ChunkBatchEnd(31));
        assertEquals(List.of(new ReplicatedChunkCache.AppliedBatch(31, 301)),
                cache.drainCompletedBatchIds());
        assertEquals(1, cache.get(reentered.dimension(), 0, 0).revision());
        assertEquals(1, loads.get());
    }

    @Test
    void leasePreventsAnOvertakenBulkBatchFromResurrectingAnUnloadedChunk() throws Exception {
        ReplicatedChunkCache cache = new ReplicatedChunkCache(null);
        ChunkColumnSnapshot old = emptyChunk(1);
        cache.accept(new CorePackets.ChunkBatchStart(40, 400, old.dimension(), 0, 0, 1));

        // Control traffic may intentionally overtake a bulk encoder queue.
        cache.accept(new CorePackets.UnloadChunk(400, old.dimension(), 0, 0));
        cache.accept(new CorePackets.ChunkColumnData(40, old));
        cache.accept(new CorePackets.ChunkBatchEnd(40));

        assertEquals(List.of(new ReplicatedChunkCache.AppliedBatch(40, 400)),
                cache.drainCompletedBatchIds());
        assertNull(cache.get(old.dimension(), 0, 0));

        ChunkColumnSnapshot current = emptyChunk(1);
        cache.accept(new CorePackets.ChunkBatchStart(41, 401, current.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(41, current));
        cache.accept(new CorePackets.ChunkBatchEnd(41));
        assertEquals(List.of(new ReplicatedChunkCache.AppliedBatch(41, 401)),
                cache.drainCompletedBatchIds());
        assertNotNull(cache.get(current.dimension(), 0, 0));
    }

    private static ChunkColumnSnapshot emptyChunk(long revision) {
        return new ChunkColumnSnapshot("skyengine:overworld", 0, 0, revision, List.of(),
                new int[ChunkColumnSnapshot.COLUMN_CELLS], new int[ChunkColumnSnapshot.TINT_CORNERS],
                new int[ChunkColumnSnapshot.TINT_CORNERS], new int[ChunkColumnSnapshot.COLUMN_CELLS]);
    }
}
