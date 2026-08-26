package de.skyengine.game.world.chunk;

import de.skyengine.core.io.IDisposable;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Gemeinsamer priorisierter CPU-Worker-Pool einer geoeffneten Spielwelt. */
public final class WorldWorkerPool implements IDisposable {

    private final ThreadPoolExecutor executor;
    /** Lange Debug-/Suchaufgaben duerfen die priorisierte Chunk-Pipeline nicht blockieren. */
    private final ExecutorService backgroundExecutor;
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
        this.backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "World Background Worker");
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

    public <T> CompletableFuture<T> submitBackground(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, this.backgroundExecutor);
    }

    @Override
    public void dispose() {
        this.backgroundExecutor.shutdownNow();
        this.executor.shutdownNow();
        try {
            this.backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS);
            if (!this.executor.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("World-Worker haben nach 2 s nicht terminiert");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
