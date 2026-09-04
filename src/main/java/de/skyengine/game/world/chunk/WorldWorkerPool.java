package de.skyengine.game.world.chunk;

import de.skyengine.core.io.IDisposable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gemeinsamer CPU-Worker-Pool einer geoeffneten Spielwelt.
 *
 * <p>Prioritaet 0 ist fuer kurze, direkt sichtbare Spieler-Remeshes reserviert. Die normalen
 * Pipelineklassen 1..3 werden gewichtet fair bedient (8:4:2). Ein klassischer
 * {@code PriorityBlockingQueue}-Pool liess im Integrated Server entweder Terrain/Licht oder
 * Client-Decode/Meshing bei dauerhaftem Flug vollstaendig verhungern. Die getrennten FIFO-Lanes
 * behalten die Nahe-/Blickrichtungsreihenfolge innerhalb jeder Klasse bei, waehrend die Gewichte
 * fuer garantierten Fortschritt auf beiden Seiten des LocalTransport sorgen.
 */
public final class WorldWorkerPool implements IDisposable {

    /** Handle for work that may be withdrawn while it is still waiting in a lane. */
    public final class TaskHandle {
        private static final int QUEUED = 0, RUNNING = 1, DONE = 2, CANCELLED = 3;
        private final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(QUEUED);
        private final Runnable task;
        private final Runnable cancelled;

        private TaskHandle(Runnable task, Runnable cancelled) {
            this.task = task;
            this.cancelled = cancelled;
        }

        private void runTask() {
            if (!this.state.compareAndSet(QUEUED, RUNNING)) return;
            /* Publish RUNNING before clearing QUEUED so awaitIdle() can never observe a
               transient zero/zero gap while ownership moves from the lane to a worker. */
            activeTasks.incrementAndGet();
            queuedTasks.decrementAndGet();
            try {
                this.task.run();
            } finally {
                activeTasks.decrementAndGet();
                this.state.set(DONE);
            }
        }

        public boolean cancel() {
            if (!this.state.compareAndSet(QUEUED, CANCELLED)) return false;
            queuedTasks.decrementAndGet();
            this.cancelled.run();
            return true;
        }

        public boolean queued() { return this.state.get() == QUEUED; }
    }

    private static final int[] FAIR_SCHEDULE = {
            1, 1, 1, 1, 1, 1, 1, 1,
            2, 2, 2, 2,
            3, 3
    };

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<Runnable>[] queues = new ConcurrentLinkedQueue[]{
            new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(),
            new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>()
    };
    private final Semaphore available = new Semaphore(0);
    private final ExecutorService executor;
    /** Lange Debug-/Suchaufgaben duerfen die priorisierte Chunk-Pipeline nicht blockieren. */
    private final ExecutorService backgroundExecutor;
    private final AtomicInteger fairCursor = new AtomicInteger();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final int workerCount;
    private volatile boolean running = true;

    public WorldWorkerPool() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
    }

    public WorldWorkerPool(int workerCount) {
        if (workerCount < 1) throw new IllegalArgumentException("workerCount muss positiv sein");
        this.workerCount = workerCount;
        this.executor = Executors.newFixedThreadPool(workerCount, Thread.ofPlatform().daemon()
                .name("World Worker-", 0).priority(Thread.NORM_PRIORITY - 1).factory());
        for (int i = 0; i < workerCount; i++) this.executor.execute(this::workerLoop);
        this.backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "World Background Worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    public int workerCount() {
        return this.workerCount;
    }

    /**
     * Reiht CPU-Arbeit stabil priorisiert ein. Gemeinsame Integrated-Server-Pools verwenden
     * dadurch ausschliesslich vergleichbare Queue-Eintraege.
     */
    public void execute(int priority, Runnable task) {
        executeCancellable(priority, task, () -> { });
    }

    public TaskHandle executeCancellable(int priority, Runnable task, Runnable onCancelled) {
        if (task == null) throw new NullPointerException("task");
        if (onCancelled == null) throw new NullPointerException("onCancelled");
        if (!this.running) throw new RejectedExecutionException("World worker pool is closed");
        int lane = Math.max(0, Math.min(this.queues.length - 1, priority));
        this.queuedTasks.incrementAndGet();
        TaskHandle handle = new TaskHandle(task, onCancelled);
        if (!this.running) {
            this.queuedTasks.decrementAndGet();
            throw new RejectedExecutionException("World worker pool is closed");
        }
        this.queues[lane].add(handle::runTask);
        this.available.release();
        return handle;
    }

    public int queuedTasks() { return this.queuedTasks.get(); }
    public int activeTasks() { return this.activeTasks.get(); }

    /** Barriere fuer Dimension-/Server-Abbau; neue Arbeit darf waehrenddessen nicht entstehen. */
    public boolean awaitIdle(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while ((this.queuedTasks.get() != 0 || this.activeTasks.get() != 0)
                && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        return this.queuedTasks.get() == 0 && this.activeTasks.get() == 0;
    }

    private void workerLoop() {
        while (this.running || this.queuedTasks.get() > 0) {
            try {
                this.available.acquire();
            } catch (InterruptedException interrupted) {
                if (!this.running) return;
                continue;
            }
            Runnable task = this.pollFair();
            if (task == null) continue;
            try {
                task.run();
            } catch (Throwable failure) {
                /* A broken task must never kill a permanent pool worker, but hiding it makes a
                   permanently missing chunk almost impossible to diagnose. Jobs which publish
                   their own failure normally do not escape this boundary. */
                Thread thread = Thread.currentThread();
                Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
                if (handler != null) handler.uncaughtException(thread, failure);
            }
        }
    }

    private Runnable pollFair() {
        Runnable urgent = this.queues[0].poll();
        if (urgent != null) return urgent;
        int preferred = FAIR_SCHEDULE[Math.floorMod(
                this.fairCursor.getAndIncrement(), FAIR_SCHEDULE.length)];
        Runnable selected = this.queues[preferred].poll();
        if (selected != null) return selected;
        for (int lane = 1; lane < this.queues.length; lane++) {
            if (lane == preferred) continue;
            selected = this.queues[lane].poll();
            if (selected != null) return selected;
        }
        return this.queues[0].poll();
    }

    public <T> CompletableFuture<T> submitBackground(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, this.backgroundExecutor);
    }

    @Override
    public void dispose() {
        this.running = false;
        this.available.release(this.workerCount);
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
