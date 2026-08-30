package de.skyengine.game.world.chunk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkManagerLodSchedulingTest {

    @Test
    void playerOriginSurvivesMergedSystemDirtyMarks() {
        Chunk chunk = new Chunk(0, 0);
        chunk.markSectionDirty(2, true);
        chunk.markSectionDirty(3);
        Chunk.DirtySections dirty = chunk.consumeDirtySections();
        assertEquals((1 << 2) | (1 << 3), dirty.mask());
        assertTrue(dirty.player());
    }

    @Test
    void normalLodUsesBoundedWorkerWavesWhenForegroundQueueIsEmpty() {
        assertEquals(48, ChunkManager.normalLodSubmissionBudget(12, 0));
        assertEquals(31, ChunkManager.normalLodSubmissionBudget(12, 17));
        assertEquals(0, ChunkManager.normalLodSubmissionBudget(12, 48));
    }

    @Test
    void foregroundWorkDoesNotForbidLodAdmission() {
        assertEquals(48, ChunkManager.normalLodSubmissionBudget(12, 0));
        assertEquals(45, ChunkManager.normalLodSubmissionBudget(12, 3));
    }

    @Test
    void lodPriorityCombinesDistanceBandLevelAndView() {
        var nearL2 = new ChunkManager.LodPriority(4, 2, 2, 1, 100.0);
        var farL1 = new ChunkManager.LodPriority(4, 8, 1, 0, 10_000.0);
        var sameBandL1 = new ChunkManager.LodPriority(4, 2, 1, 1, 120.0);
        assertTrue(ChunkManager.compareLodPriorities(nearL2, farL1) < 0);
        assertTrue(ChunkManager.compareLodPriorities(sameBandL1, nearL2) < 0);
    }

    @Test
    void idleForegroundLetsLodUseEveryWorker() throws Exception {
        ChunkManager manager = new ChunkManager(null, null, 4);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                int distance = i;
                assertTrue(manager.submitLodTask(() -> {
                    started.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, new ChunkManager.LodPriority(1, distance, 1, 0, distance), () -> {}));
            }
            assertTrue(started.await(2, TimeUnit.SECONDS),
                    "Ohne Vordergrundlast darf LOD die gesamte Worker-Kapazitaet nutzen");
        } finally {
            release.countDown();
            manager.dispose();
        }
    }

    @Test
    void fullQueueActivelyEvictsWorstLodForNearerRequest() throws Exception {
        ChunkManager manager = new ChunkManager(null, null, 2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger discarded = new AtomicInteger();
        try {
            Runnable blocker = () -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            for (int i = 0; i < 2; i++) {
                assertTrue(manager.submitLodTask(blocker,
                        new ChunkManager.LodPriority(1, 20 + i, 5, 1, 100_000 + i),
                        discarded::incrementAndGet));
            }
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 6; i++) {
                assertTrue(manager.submitLodTask(() -> {},
                        new ChunkManager.LodPriority(1, 10 + i, 4, 1, 10_000 + i),
                        discarded::incrementAndGet));
            }
            assertEquals(0, manager.normalLodSubmissionBudget());
            assertTrue(manager.submitLodTask(() -> {},
                    new ChunkManager.LodPriority(1, 0, 1, 0, 1),
                    discarded::incrementAndGet));
            assertEquals(1, discarded.get(), "Die schlechteste wartende Anfrage muss weichen");
            assertFalse(manager.submitLodTask(() -> {},
                    new ChunkManager.LodPriority(1, 99, 5, 1, 999_999),
                    discarded::incrementAndGet));
        } finally {
            release.countDown();
            manager.dispose();
        }
    }

    @Test
    void anchorChangePurgesWaitingLodAndCallsDiscardHook() throws Exception {
        ChunkManager manager = new ChunkManager(null, null, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger discarded = new AtomicInteger();
        try {
            assertTrue(manager.submitLodTask(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, new ChunkManager.LodPriority(1, 0, 1, 0, 1), discarded::incrementAndGet));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) {
                assertTrue(manager.submitLodTask(() -> {},
                        new ChunkManager.LodPriority(1, i + 1, 2, 0, i + 2),
                        discarded::incrementAndGet));
            }
            manager.updateLodScheduleVersion(2);
            assertEquals(3, discarded.get(), "Alle veralteten wartenden LOD-Jobs müssen weichen");
            assertEquals(3, manager.normalLodSubmissionBudget());
        } finally {
            release.countDown();
            manager.dispose();
        }
    }

    @Test
    void deferredRemeshMarkerDoesNotStarveNormalLod() throws Exception {
        ChunkManager manager = new ChunkManager(null, null);
        try {
            Field field = ChunkManager.class.getDeclaredField("remeshQueue");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<Chunk> markers = (ConcurrentLinkedQueue<Chunk>) field.get(manager);
            markers.add(new Chunk(0, 0));

            assertTrue(manager.normalLodSubmissionBudget() > 0,
                    "Ein zurückgestellter Remesh-Marker darf normale LOD-Jobs nicht sperren");
        } finally {
            manager.dispose();
        }
    }
}
