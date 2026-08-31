package de.skyengine.graphics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

final class PerformanceProfilerTest {
    private final PerformanceProfiler profiler = new PerformanceProfiler();

    @AfterEach
    void disable() { this.profiler.setEnabled(false); }

    @Test
    void calculatesCurrentMeanP95MaximumAndRate() {
        this.profiler.setEnabled(true);
        for (int i = 1; i <= 100; i++) {
            this.profiler.record(PerformanceProfiler.WorkerSection.L0_REMESH, i * 1_000L);
        }
        PerformanceProfiler.TimingStats stats = this.profiler.publishSnapshot().l0()
                .get(PerformanceProfiler.WorkerSection.L0_REMESH);
        assertEquals(100_000, stats.currentNanos());
        assertEquals(50_500, stats.meanNanos());
        assertEquals(95_000, stats.p95Nanos());
        assertEquals(100_000, stats.maxNanos());
        assertEquals(100, stats.samples());
        assertTrue(stats.jobsPerSecond() > 0);
    }

    @Test
    void disabledHooksDoNotCreateSamplesAndResetDropsOldGeneration() {
        assertEquals(0, this.profiler.begin());
        this.profiler.record(PerformanceProfiler.CpuSection.FRAME, 10);
        assertTrue(this.profiler.snapshot().cpu().isEmpty());

        this.profiler.setEnabled(true);
        this.profiler.record(PerformanceProfiler.CpuSection.FRAME, 20);
        long generation = this.profiler.publishSnapshot().generation();
        long oldMeasurement = this.profiler.begin();
        this.profiler.reset();
        this.profiler.recordElapsed(PerformanceProfiler.CpuSection.FRAME, oldMeasurement);
        assertTrue(this.profiler.snapshot().cpu().isEmpty());
        assertTrue(this.profiler.snapshot().generation() > generation);
    }

    @Test
    void concurrentWorkersLoseNoSamples() throws Exception {
        this.profiler.setEnabled(true);
        int workers = 4, jobs = 500;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            Thread thread = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                for (int job = 0; job < jobs; job++) {
                    this.profiler.record(PerformanceProfiler.WorkerSection.L0_REMESH, 100L);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();

        PerformanceProfiler.ProfilerSnapshot snapshot = this.profiler.publishSnapshot();
        PerformanceProfiler.TimingStats stats = snapshot.l0()
                .get(PerformanceProfiler.WorkerSection.L0_REMESH);
        assertEquals((long) workers * jobs, stats.samples());
        assertEquals(100L, stats.currentNanos());
    }

    @Test
    void asyncQueueMeasurementCrossesThreadsButNotResetGeneration() throws Exception {
        this.profiler.setEnabled(true);
        PerformanceProfiler.AsyncToken accepted = this.profiler.beginAsync();
        Thread worker = new Thread(() -> this.profiler.recordElapsed(
                PerformanceProfiler.WorkerSection.L0_UPLOAD_WAIT, accepted));
        worker.start();
        worker.join();
        PerformanceProfiler.TimingStats acceptedStats = this.profiler.publishSnapshot().l0()
                .get(PerformanceProfiler.WorkerSection.L0_UPLOAD_WAIT);
        assertEquals(1, acceptedStats.samples());
        assertTrue(acceptedStats.currentNanos() > 0);

        PerformanceProfiler.AsyncToken stale = this.profiler.beginAsync();
        this.profiler.reset();
        this.profiler.record(PerformanceProfiler.WorkerSection.L0_UPLOAD_WAIT, 99, stale);
        assertTrue(this.profiler.snapshot().l0().isEmpty());
    }
}
