package de.skyengine.game.world.chunk;

import de.skyengine.core.io.IDisposable;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Gemeinsamer priorisierter CPU-Worker-Pool einer geoeffneten Spielwelt. */
public final class WorldWorkerPool implements IDisposable {

    private final ThreadPoolExecutor executor;
    private final AtomicLong sequence = new AtomicLong();
    private final int workerCount;

    public WorldWorkerPool() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
    }

    public WorldWorkerPool(int workerCount) {
        if (workerCount < 1) throw new IllegalArgumentException("workerCount muss positiv sein");
        this.workerCount = workerCount;
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "World Worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    int workerCount() {
        return this.workerCount;
    }

    long nextSequence() {
        return this.sequence.getAndIncrement();
    }

    ThreadPoolExecutor executor() {
        return this.executor;
    }

    @Override
    public void dispose() {
        this.executor.shutdownNow();
        try {
            if (!this.executor.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("World-Worker haben nach 2 s nicht terminiert");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
