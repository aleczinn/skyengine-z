package de.skyengine.client.network;

import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.BlockEntitySnapshot;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplicatedChunkCacheTest {
    @Test
    void dependencyHaloInstallsForNeighbourReadsWithoutBecomingVisible() throws Exception {
        AtomicReference<Boolean> installedVisible = new AtomicReference<>();
        AtomicReference<Boolean> visibilityUpdate = new AtomicReference<>();
        ReplicatedChunkCache cache = new ReplicatedChunkCache(new ReplicatedChunkCache.Listener() {
            @Override public void chunkLoaded(ChunkColumnSnapshot chunk) { }
            @Override public java.util.concurrent.CompletionStage<Void> chunkLoadedAsync(
                    ChunkColumnSnapshot chunk, boolean visible) {
                installedVisible.set(visible);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public void chunkVisibilityChanged(String dimension, int chunkX, int chunkZ,
                                                         boolean visible) {
                visibilityUpdate.set(visible);
            }
            @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) { }
            @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                                List<BlockChange> changes) { }
        });
        cache.accept(new CorePackets.ChunkViewUpdate("skyengine:overworld", 0, 0, 2, 1, 1));
        ChunkColumnSnapshot halo = emptyChunk(3, 1);
        cache.accept(new CorePackets.ChunkBatchStart(70, 700, 1,
                halo.dimension(), halo.chunkX(), halo.chunkZ(), 1));
        cache.accept(new CorePackets.ChunkColumnData(70, halo));
        cache.accept(new CorePackets.ChunkBatchEnd(70));

        assertEquals(Boolean.FALSE, installedVisible.get());
        assertNotNull(cache.get(halo.dimension(), halo.chunkX(), halo.chunkZ()),
                "dependency data remains available to collision/light/edge reads");

        cache.accept(new CorePackets.ChunkViewUpdate("skyengine:overworld", 1, 0, 2, 1, 2));
        assertEquals(Boolean.TRUE, visibilityUpdate.get(),
                "a retained halo column can become visible without retransmission");
    }

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
    void wholeSnapshotCompressionIsDecodedOnceBeforeAtomicInstall() throws Exception {
        ReplicatedChunkCache cache = new ReplicatedChunkCache(null);
        ChunkColumnSnapshot chunk = emptyChunk(13);
        byte[] encoded = de.skyengine.shared.network.CoreProtocol.encodeChunkSnapshot(chunk);
        byte[] compressed = com.github.luben.zstd.Zstd.compress(encoded, 3);

        cache.accept(new CorePackets.ChunkBatchStart(13, 130, chunk.dimension(), 0, 0, 1));
        cache.accept(CorePackets.ChunkColumnFragment.takeOwnership(
                13, 0, 1, compressed.length, encoded.length, compressed));
        cache.accept(new CorePackets.ChunkBatchEnd(13));
        cache.drainCompletedBatchIds();

        assertNotNull(cache.get(chunk.dimension(), 0, 0));
        assertEquals(13, cache.get(chunk.dimension(), 0, 0).revision());
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

    @Test
    void viewEpochEvictsTrailsAndRejectsLateOldViewSnapshots() throws Exception {
        ReplicatedChunkCache cache = new ReplicatedChunkCache(null);
        ChunkColumnSnapshot origin = emptyChunk(0, 1);
        cache.accept(new CorePackets.ChunkBatchStart(50, 500, 0,
                origin.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(50, origin));
        cache.accept(new CorePackets.ChunkBatchEnd(50));
        assertNotNull(cache.get(origin.dimension(), 0, 0));

        cache.accept(new CorePackets.ChunkViewUpdate(origin.dimension(), 10, 0, 2, 1, 2));
        assertNull(cache.get(origin.dimension(), 0, 0));

        cache.accept(new CorePackets.ChunkBatchStart(51, 501, 1,
                origin.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(51, origin));
        cache.accept(new CorePackets.ChunkBatchEnd(51));
        assertNull(cache.get(origin.dimension(), 0, 0));
        assertEquals(List.of(new ReplicatedChunkCache.AppliedBatch(50, 500),
                        new ReplicatedChunkCache.AppliedBatch(51, 501)),
                cache.drainCompletedBatchIds());
    }

    @Test
    void lateOldEpochStillInstallsAChunkRetainedByTheNewView() throws Exception {
        ReplicatedChunkCache cache = new ReplicatedChunkCache(null);
        ChunkColumnSnapshot retained = emptyChunk(2, 1);
        cache.accept(new CorePackets.ChunkBatchStart(52, 502, 1,
                retained.dimension(), 2, 0, 1));

        // Center moved, but chunk 2/0 remains inside the new view.
        cache.accept(new CorePackets.ChunkViewUpdate(retained.dimension(), 3, 0, 2, 1, 2));
        cache.accept(new CorePackets.ChunkColumnData(52, retained));
        cache.accept(new CorePackets.ChunkBatchEnd(52));

        assertNotNull(cache.get(retained.dimension(), 2, 0));
        assertEquals(List.of(new ReplicatedChunkCache.AppliedBatch(52, 502)),
                cache.drainCompletedBatchIds());
    }

    @Test
    void newerDeltaCannotBeOverwrittenByAnOlderPreparedSnapshot() throws Exception {
        java.util.concurrent.CompletableFuture<Void> delayed = new java.util.concurrent.CompletableFuture<>();
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger discarded = new AtomicInteger();
        ReplicatedChunkCache cache = new ReplicatedChunkCache(new ReplicatedChunkCache.Listener() {
            @Override public void chunkLoaded(ChunkColumnSnapshot chunk) { }
            @Override public java.util.concurrent.CompletionStage<Void> chunkLoadedAsync(
                    ChunkColumnSnapshot chunk) {
                return preparations.getAndIncrement() == 0
                        ? java.util.concurrent.CompletableFuture.completedFuture(null) : delayed;
            }
            @Override public void discardDecodedChunk(ChunkColumnSnapshot chunk) { discarded.incrementAndGet(); }
            @Override public void chunkUnloaded(String dimension, int chunkX, int chunkZ) { }
            @Override public void blocksChanged(String dimension, int chunkX, int chunkZ, long revision,
                                                List<BlockChange> changes) { }
        });
        ChunkColumnSnapshot baseline = emptyChunk(1);
        cache.accept(new CorePackets.ChunkBatchStart(60, 600, baseline.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(60, baseline));
        cache.accept(new CorePackets.ChunkBatchEnd(60));
        cache.drainCompletedBatchIds();

        ChunkColumnSnapshot olderSnapshot = emptyChunk(2);
        cache.accept(new CorePackets.ChunkBatchStart(61, 601, olderSnapshot.dimension(), 0, 0, 1));
        cache.accept(new CorePackets.ChunkColumnData(61, olderSnapshot));
        cache.accept(new CorePackets.ChunkBatchEnd(61));
        cache.accept(new CorePackets.BlockUpdate(olderSnapshot.dimension(), 0, 0, 3,
                new BlockChange(1, 2, 3, 0)));
        delayed.complete(null);
        cache.drainCompletedBatchIds();

        assertEquals(3, cache.get(olderSnapshot.dimension(), 0, 0).revision());
        assertEquals(1, discarded.get());
    }

    private static ChunkColumnSnapshot emptyChunk(long revision) {
        return emptyChunk(0, revision);
    }

    private static ChunkColumnSnapshot emptyChunk(int chunkX, long revision) {
        return new ChunkColumnSnapshot("skyengine:overworld", chunkX, 0, revision, List.of(),
                new int[ChunkColumnSnapshot.COLUMN_CELLS], new int[ChunkColumnSnapshot.TINT_CORNERS],
                new int[ChunkColumnSnapshot.TINT_CORNERS], new int[ChunkColumnSnapshot.COLUMN_CELLS]);
    }
}
