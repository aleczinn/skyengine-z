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
 * <p>Prioritaet 0 ist fuer kurze, direkt sichtbare Spieler-Remeshes vorgesehen. Alle
 * Pipelineklassen werden gewichtet fair bedient (8:8:4:2) und erhalten zusaetzlich Aging. Ein klassischer
 * {@code PriorityBlockingQueue}-Pool liess im Integrated Server entweder Terrain/Licht oder
 * Client-Decode/Meshing bei dauerhaftem Flug vollstaendig verhungern. Die getrennten FIFO-Lanes
 * behalten die Nahe-/Blickrichtungsreihenfolge innerhalb jeder Klasse bei, waehrend die Gewichte
 * fuer garantierten Fortschritt auf beiden Seiten des LocalTransport sorgen.
 */
public final class WorldWorkerPool implements IDisposable {
    public record LaneStats(int queued, int running, long completed,
                            long oldestQueuedAgeNanos, long queueWaitMedianNanos,
                            long queueWaitP95Nanos, double completedPerSecond) { }

    /** Handle for work that may be withdrawn while it is still waiting in a lane. */
    public final class TaskHandle {
        private static final int QUEUED = 0, RUNNING = 1, DONE = 2, CANCELLED = 3;
        private final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(QUEUED);
        private final Runnable task;
        private final Runnable cancelled;
        private final int lane;
        private final long queuedNanos = System.nanoTime();

        private TaskHandle(Runnable task, Runnable cancelled, int lane) {
            this.task = task;
            this.cancelled = cancelled;
            this.lane = lane;
        }

        private void runTask() {
            if (!this.state.compareAndSet(QUEUED, RUNNING)) return;
            /* Publish RUNNING before clearing QUEUED so awaitIdle() can never observe a
               transient zero/zero gap while ownership moves from the lane to a worker. */
            activeTasks.incrementAndGet();
            activeByLane[this.lane].incrementAndGet();
            queuedTasks.decrementAndGet();
            queuedByLane[this.lane].decrementAndGet();
            waitByLane[this.lane].add(Math.max(0, System.nanoTime() - this.queuedNanos));
            try {
                this.task.run();
            } finally {
                activeTasks.decrementAndGet();
                activeByLane[this.lane].decrementAndGet();
                completedByLane[this.lane].incrementAndGet();
                this.state.set(DONE);
            }
        }

        public boolean cancel() {
            if (!this.state.compareAndSet(QUEUED, CANCELLED)) return false;
            queuedTasks.decrementAndGet();
            queuedByLane[this.lane].decrementAndGet();
            this.cancelled.run();
            return true;
        }

        public boolean queued() { return this.state.get() == QUEUED; }
    }

    private static final int[] FAIR_SCHEDULE = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 1, 1, 1, 1,
            2, 2, 2, 2,
            3, 3
    };
    private static final long STARVATION_NANOS = 250_000_000L;

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<TaskHandle>[] queues = new ConcurrentLinkedQueue[]{
            new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(),
            new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>()
    };
    private final Semaphore available = new Semaphore(0);
    private final ExecutorService executor;
    /** Lange Debug-/Suchaufgaben duerfen die priorisierte Chunk-Pipeline nicht blockieren. */
    private final ExecutorService backgroundExecutor;
    private final AtomicInteger fairCursor = new AtomicInteger();
    private final AtomicInteger fallbackCursor = new AtomicInteger();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final AtomicInteger[] queuedByLane = counters(4);
    private final AtomicInteger[] activeByLane = counters(4);
    private final java.util.concurrent.atomic.AtomicLong[] completedByLane = longCounters(4);
    private final WaitSeries[] waitByLane = waitSeries(4);
    private final int workerCount;
    private final long startedNanos = System.nanoTime();
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

    /** Adapter for transport/persistence stages participating in the same fair CPU scheduler. */
    public java.util.concurrent.Executor executorForLane(int priority) {
        int lane = Math.max(0, Math.min(this.queues.length - 1, priority));
        return command -> execute(lane, command);
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
        this.queuedByLane[lane].incrementAndGet();
        TaskHandle handle = new TaskHandle(task, onCancelled, lane);
        if (!this.running) {
            this.queuedTasks.decrementAndGet();
            this.queuedByLane[lane].decrementAndGet();
            throw new RejectedExecutionException("World worker pool is closed");
        }
        this.queues[lane].add(handle);
        this.available.release();
        return handle;
    }

    public int queuedTasks() { return this.queuedTasks.get(); }
    public int activeTasks() { return this.activeTasks.get(); }

    public LaneStats laneStats(int lane) {
        if (lane < 0 || lane >= this.queues.length) throw new IllegalArgumentException("Invalid lane");
        TaskHandle first = this.queues[lane].peek();
        long age = first != null && first.queued()
                ? Math.max(0, System.nanoTime() - first.queuedNanos) : 0;
        long[] waits = this.waitByLane[lane].snapshot();
        double seconds = Math.max(1.0e-9, (System.nanoTime() - this.startedNanos) / 1_000_000_000.0);
        return new LaneStats(this.queuedByLane[lane].get(), this.activeByLane[lane].get(),
                this.completedByLane[lane].get(), age, waits[0], waits[1],
                this.completedByLane[lane].get() / seconds);
    }

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
            TaskHandle task = this.pollFair();
            if (task == null) continue;
            try {
                task.runTask();
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

    private TaskHandle pollFair() {
        int agedLane = oldestStarvedLane();
        if (agedLane >= 0) return this.queues[agedLane].poll();
        int preferred = FAIR_SCHEDULE[Math.floorMod(
                this.fairCursor.getAndIncrement(), FAIR_SCHEDULE.length)];
        TaskHandle selected = this.queues[preferred].poll();
        if (selected != null) return selected;
        /* Redistribute the weight of an empty lane round-robin across the lanes which really
           have work. Always scanning from lane 0 made lane 1 consume almost every unused
           lane-0/lane-3 slot; with worldgen and client meshing active, meshing received only
           about 18% of an otherwise shared 30-worker pool. */
        int fallbackStart = Math.floorMod(this.fallbackCursor.getAndIncrement(), this.queues.length);
        for (int offset = 0; offset < this.queues.length; offset++) {
            int lane = (fallbackStart + offset) % this.queues.length;
            if (lane == preferred) continue;
            selected = this.queues[lane].poll();
            if (selected != null) return selected;
        }
        return null;
    }

    private int oldestStarvedLane() {
        long now = System.nanoTime(), oldest = STARVATION_NANOS;
        int selected = -1;
        for (int lane = 0; lane < this.queues.length; lane++) {
            TaskHandle task = this.queues[lane].peek();
            if (task == null || !task.queued()) continue;
            long age = now - task.queuedNanos;
            if (age >= oldest) { oldest = age; selected = lane; }
        }
        return selected;
    }

    private static AtomicInteger[] counters(int size) {
        AtomicInteger[] values = new AtomicInteger[size];
        java.util.Arrays.setAll(values, ignored -> new AtomicInteger());
        return values;
    }

    private static java.util.concurrent.atomic.AtomicLong[] longCounters(int size) {
        java.util.concurrent.atomic.AtomicLong[] values = new java.util.concurrent.atomic.AtomicLong[size];
        java.util.Arrays.setAll(values, ignored -> new java.util.concurrent.atomic.AtomicLong());
        return values;
    }

    private static WaitSeries[] waitSeries(int size) {
        WaitSeries[] values = new WaitSeries[size];
        java.util.Arrays.setAll(values, ignored -> new WaitSeries());
        return values;
    }

    private static final class WaitSeries {
        private final long[] samples = new long[1024];
        private int next, size;
        synchronized void add(long value) {
            this.samples[this.next] = value;
            this.next = (this.next + 1) % this.samples.length;
            if (this.size < this.samples.length) this.size++;
        }
        synchronized long[] snapshot() {
            if (this.size == 0) return new long[]{0, 0};
            long[] sorted = java.util.Arrays.copyOf(this.samples, this.size);
            java.util.Arrays.sort(sorted);
            return new long[]{sorted[(this.size - 1) / 2],
                    sorted[Math.max(0, (int) Math.ceil(this.size * 0.95) - 1)]};
        }
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
