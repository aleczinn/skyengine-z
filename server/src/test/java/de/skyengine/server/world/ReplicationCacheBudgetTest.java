package de.skyengine.server.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationCacheBudgetTest {
    @Test
    void allLayersShareOneCeilingAndLeasesReleaseExactlyOnce() {
        ReplicationCacheBudget budget = new ReplicationCacheBudget(100);
        assertTrue(budget.reserve(ReplicationCacheBudget.Layer.LOGICAL_SNAPSHOT, 60));
        assertFalse(budget.reserve(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD, 50));
        assertTrue(budget.reserve(ReplicationCacheBudget.Layer.ENCODED_PAYLOAD, 40));
        long lease = budget.acquireLease(60);
        assertEquals(1, budget.metrics().activeSnapshotLeases());
        budget.releaseLease(lease);
        budget.releaseLease(lease);
        assertEquals(0, budget.metrics().activeSnapshotLeases());
        assertEquals(100, budget.metrics().replicationCacheBytesTotal());
    }

    @Test
    void pinnedRevisionsAndTrafficAreMeasuredWithoutConsumingCacheCapacity() {
        ReplicationCacheBudget budget = new ReplicationCacheBudget(128);
        Object revision = new Object();
        long first = budget.acquireLease("player-a", revision, 40);
        long second = budget.acquireLease("player-b", revision, 40);
        budget.snapshotAllocated(70);
        budget.copied(20);
        budget.wireProduced(15);
        budget.encodedReuseSaved(10);
        budget.compressedReuseSaved(5);

        assertEquals(2, budget.metrics().activeSnapshotLeases());
        assertEquals(1, budget.metrics().pinnedRevisionCount());
        assertEquals(40, budget.metrics().cachePinnedBytes(),
                "two recipients share one physically pinned immutable revision");
        assertEquals(70, budget.trafficMetrics().snapshotBytesAllocated());
        assertEquals(20, budget.trafficMetrics().bytesCopied());
        assertEquals(15, budget.trafficMetrics().wireBytesProduced());

        budget.releaseLease(first);
        budget.releaseLease(second);
        assertEquals(0, budget.metrics().pinnedRevisionCount());
        assertEquals(0, budget.metrics().cachePinnedBytes());
    }

    @Test
    void transferAdmissionHonoursGlobalPinBudgetButAllowsRevisionSharing() {
        ReplicationCacheBudget budget = new ReplicationCacheBudget(100);
        Object shared = new Object();
        long first = budget.tryAcquireLease("a", shared, 80);
        long second = budget.tryAcquireLease("b", shared, 80);
        long rejected = budget.tryAcquireLease("c", new Object(), 30);

        assertTrue(first != 0);
        assertTrue(second != 0);
        assertEquals(0, rejected);
        assertEquals(80, budget.metrics().cachePinnedBytes());
        budget.releaseLease(first);
        assertEquals(80, budget.metrics().cachePinnedBytes());
        budget.releaseLease(second);
        assertEquals(0, budget.metrics().cachePinnedBytes());
    }

    @Test
    void debugLeaseTrackingNamesTheOwnerAndClearsAfterRelease() {
        ReplicationCacheBudget budget = new ReplicationCacheBudget(100);
        long lease = budget.acquireLease("player-a/chunk-2,7", new Object(), 40);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                budget::assertNoActiveLeases);
        assertTrue(failure.getMessage().contains("player-a/chunk-2,7"));
        assertEquals("player-a/chunk-2,7", budget.activeLeases().getFirst().owner());

        budget.releaseLease(lease);
        budget.assertNoActiveLeases();
    }
}
