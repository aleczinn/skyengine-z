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

    @Test
    void oneSharedPoolIsWorkConservingAcrossServerAndClientLanes() throws Exception {
        WorldWorkerPool workers = new WorldWorkerPool(4);
        try {
            CountDownLatch allRunning = new CountDownLatch(4);
            CountDownLatch release = new CountDownLatch(1);
            for (int i = 0; i < 12; i++) {
                workers.execute(2, () -> {
                    allRunning.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            }
            assertTrue(allRunning.await(5, TimeUnit.SECONDS),
                    "all shared workers must help client decode/meshing when server lanes are idle");
            assertEquals(4, workers.laneStats(2).running());
            release.countDown();
            assertTrue(workers.awaitIdle(5, TimeUnit.SECONDS));

            CountDownLatch serverCompleted = new CountDownLatch(12);
            for (int i = 0; i < 12; i++) workers.execute(1, serverCompleted::countDown);
            assertTrue(serverCompleted.await(5, TimeUnit.SECONDS),
                    "the same workers must immediately return to world generation");
            assertTrue(workers.awaitIdle(5, TimeUnit.SECONDS));
        } finally {
            workers.dispose();
        }
    }

    @Test
    void emptyLaneCapacityIsNotAlwaysDonatedToTheLowestNumberedBusyLane() throws Exception {
        WorldWorkerPool workers = new WorldWorkerPool(1);
        try {
            CountDownLatch blockerRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            java.util.List<Integer> order = new java.util.concurrent.CopyOnWriteArrayList<>();
            workers.execute(1, () -> {
                blockerRunning.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++) workers.execute(1, () -> order.add(1));
            for (int i = 0; i < 64; i++) workers.execute(2, () -> order.add(2));
            release.countDown();
            assertTrue(workers.awaitIdle(5, TimeUnit.SECONDS));
            int firstWindow = Math.min(32, order.size());
            long clientJobs = order.subList(0, firstWindow).stream().filter(value -> value == 2).count();
            assertTrue(clientJobs >= 8,
                    "empty schedule lanes must be redistributed fairly, observed " + clientJobs);
        } finally {
            workers.dispose();
        }
    }
}
