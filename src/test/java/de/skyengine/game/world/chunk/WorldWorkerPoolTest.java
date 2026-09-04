package de.skyengine.game.world.chunk;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldWorkerPoolTest {
    @Test
    void queuedTaskCanBeCancelledWithoutConsumingAWorker() throws Exception {
        WorldWorkerPool workers = new WorldWorkerPool(1);
        try {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger cancelledRuns = new AtomicInteger();
            AtomicInteger cancellationCallbacks = new AtomicInteger();
            workers.execute(1, () -> {
                running.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            WorldWorkerPool.TaskHandle handle = workers.executeCancellable(1,
                    cancelledRuns::incrementAndGet, cancellationCallbacks::incrementAndGet);

            assertTrue(handle.cancel());
            release.countDown();
            assertTrue(workers.awaitIdle(5, TimeUnit.SECONDS));
            assertEquals(0, cancelledRuns.get());
            assertEquals(1, cancellationCallbacks.get());
        } finally {
            workers.dispose();
        }
    }

    @Test
    void lowWeightLaneCannotStarveBehindContinuousWorldGeneration() throws Exception {
        WorldWorkerPool workers = new WorldWorkerPool(1);
        try {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch lowWeightRan = new CountDownLatch(1);
            AtomicInteger worldJobsCompleted = new AtomicInteger();

            workers.execute(1, () -> {
                running.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++) workers.execute(1, worldJobsCompleted::incrementAndGet);
            workers.execute(3, lowWeightRan::countDown);
            release.countDown();

            assertTrue(lowWeightRan.await(5, TimeUnit.SECONDS));
            assertTrue(worldJobsCompleted.get() < 64,
                    "weighted scheduling must run lane 3 before draining lane 1 completely");
        } finally {
            workers.dispose();
        }
    }
}
