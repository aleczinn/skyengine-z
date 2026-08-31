package de.skyengine.graphics;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * GL-unabhaengige, thread-sichere Zentrale des Ingame-Profilers. Messpunkte duerfen diese
 * Klasse auch aus Chunk-Workern aufrufen. Der deaktivierte Pfad liest nur das volatile
 * enabled-Flag; insbesondere wird dort weder die Uhr gelesen noch synchronisiert.
 */
public final class PerformanceProfiler {
    public enum CpuSection {
        CULL, COMMAND_BUILD, SUBMISSION, UPLOAD, SORT, ENTITIES, BLOCK_ENTITIES,
        PARTICLES, OVERLAYS, POSTPROCESSING, GUI, SWAP, PROFILER_UI, REST, FRAME
    }

    public enum GpuSection {
        L0_OPAQUE, L0_CUTOUT, L0_TRANSLUCENT,
        ENTITIES, BLOCK_ENTITIES, PARTICLES, HAND_OVERLAYS, RESOLVE,
        POSTPROCESSING, GUI, FRAME_SPAN
    }

    public enum TickSection {
        PLAYER_GAME_LOGIC, CHUNK_MANAGEMENT, DEFERRED_STATE_UPDATES,
        SCHEDULED_TICKS, BLOCK_EVENTS, RANDOM_TICKS, BLOCK_ENTITY_TICKS, ENERGY_NETWORKS,
        ENTITY_TICKS, PARTICLE_TICKS, REST, TOTAL
    }

    public enum WorkerSection {
        L0_QUEUE_WAIT, L0_DISK_LOAD, L0_TERRAIN, L0_FEATURES, L0_INITIAL_LIGHT,
        L0_LIGHT_UPDATE, L0_INITIAL_MESH, L0_REMESH, L0_UPLOAD_WAIT, L0_UPLOAD
    }

    /** Threaduebergreifender Startwert fuer Worker -> Render-Queue-Zeiten. */
    public record AsyncToken(long startedNanos, long generation) {}

    public enum Counter {
        TICKED_ENTITIES, TICKED_BLOCK_ENTITIES, EXECUTED_BLOCK_TICKS,
        L0_ACTIVE_JOBS, L0_WAITING_JOBS, ACTIVE_PARTICLES, REJECTED_PARTICLES,
        ACTIVE_ENERGY_NETWORKS, ENERGY_ENDPOINTS, ENERGY_TRANSFERRED, ENERGY_TOPOLOGY_REBUILDS,
        L0_FULL_CUBE_FACES, L0_COMPACT_QUADS, L0_COMPACT_STANDARD_QUADS,
        L0_COMPACT_UNIFORM_QUADS, L0_COMPACT_CORNER_QUADS, L0_CORNER_SHADING_FACES,
        L0_CORNER_SHADING_FACES_MERGED, L0_CORNER_SHADING_FACES_UNMERGED,
        L0_MERGE_REJECTED_SHADING, L0_MERGE_REJECTED_MATERIAL, L0_MERGE_REJECTED_STATE,
        L0_OVERLAY_FALLBACK_FACES,
        L0_COMPACT_STANDARD_BYTES, L0_COMPACT_UNIFORM_BYTES, L0_COMPACT_CORNER_BYTES,
        L0_COMPACT_BYTES
    }

    public record TimingStats(long currentNanos, double meanNanos, long p95Nanos,
                              long maxNanos, long samples, double jobsPerSecond) {
        public static final TimingStats EMPTY = new TimingStats(0, 0, 0, 0, 0, 0);
        public double currentMillis() { return this.currentNanos / 1_000_000.0; }
        public double meanMillis() { return this.meanNanos / 1_000_000.0; }
        public double p95Millis() { return this.p95Nanos / 1_000_000.0; }
        public double maxMillis() { return this.maxNanos / 1_000_000.0; }
    }

    public record GraphSample(long timestampNanos, double cpuFrameMillis,
                              double gpuFrameMillis, double tickMillis) {}

    public record ProfilerSnapshot(long generation, long publishedNanos,
                                   Map<CpuSection, TimingStats> cpu,
                                   Map<GpuSection, TimingStats> gpu,
                                   Map<TickSection, TimingStats> tick,
                                   Map<WorkerSection, TimingStats> l0,
                                   Map<Counter, Long> counters,
                                   List<GraphSample> graph) {}

    private static final int STAT_CAPACITY = 1024;
    private static final int GRAPH_CAPACITY = 200;
    private static final long SNAPSHOT_INTERVAL = 250_000_000L; // 4 Hz
    private static final long GRAPH_INTERVAL = 50_000_000L;     // 20 Hz
    private static final PerformanceProfiler INSTANCE = new PerformanceProfiler();

    private final EnumMap<CpuSection, Series> cpu = series(CpuSection.class);
    private final EnumMap<GpuSection, Series> gpu = series(GpuSection.class);
    private final EnumMap<TickSection, Series> tick = series(TickSection.class);
    private final EnumMap<WorkerSection, Series> l0 = series(WorkerSection.class);
    private final EnumMap<Counter, Long> counters = new EnumMap<>(Counter.class);
    private final ArrayDeque<GraphSample> graph = new ArrayDeque<>(GRAPH_CAPACITY);
    private final ThreadLocal<Long> beginGeneration = ThreadLocal.withInitial(() -> -1L);

    private volatile boolean enabled;
    private volatile long generation;
    private volatile ProfilerSnapshot snapshot = emptySnapshot(0);
    private long startedNanos;
    private long lastPublishedNanos;
    private long lastGraphNanos;
    private double pendingCpuFrame = Double.NaN;
    private double pendingGpuFrame = Double.NaN;
    private double pendingTick = Double.NaN;

    public PerformanceProfiler() {}

    public static PerformanceProfiler get() { return INSTANCE; }
    public boolean isEnabled() { return this.enabled; }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        synchronized (this) {
            if (this.enabled == enabled) return;
            resetLocked();
            this.enabled = enabled;
            if (enabled) this.startedNanos = System.nanoTime();
        }
    }

    /** Liefert 0 bei ausgeschaltetem Profiler, ohne die Uhr zu lesen. */
    public long begin() {
        if (!this.enabled) return 0L;
        this.beginGeneration.set(this.generation);
        return System.nanoTime();
    }

    /** Wie {@link #begin()}, aber reset-sicher auf einem anderen Thread auswertbar. */
    public AsyncToken beginAsync() {
        if (!this.enabled) return null;
        long captureGeneration = this.generation;
        return new AsyncToken(System.nanoTime(), captureGeneration);
    }

    public void record(CpuSection section, long nanos) {
        if (!this.enabled) return;
        this.cpu.get(section).add(nanos);
        updatePending(section, nanos);
    }

    public void record(GpuSection section, long nanos) {
        if (!this.enabled) return;
        this.gpu.get(section).add(nanos);
        if (section == GpuSection.FRAME_SPAN) synchronized (this) {
            this.pendingGpuFrame = nanos / 1_000_000.0;
        }
    }

    public void record(TickSection section, long nanos) {
        if (!this.enabled) return;
        this.tick.get(section).add(nanos);
        if (section == TickSection.TOTAL) synchronized (this) {
            this.pendingTick = nanos / 1_000_000.0;
        }
    }

    public void record(WorkerSection section, long nanos) {
        if (!this.enabled) return;
        this.l0.get(section).add(nanos);
    }

    public void record(WorkerSection section, long nanos, AsyncToken token) {
        if (!asyncMeasurementIsCurrent(token)) return;
        record(section, nanos);
    }

    public void recordElapsed(CpuSection section, long startedNanos) {
        if (!measurementIsCurrent(startedNanos)) return;
        record(section, System.nanoTime() - startedNanos);
    }

    public void recordElapsed(TickSection section, long startedNanos) {
        if (!measurementIsCurrent(startedNanos)) return;
        record(section, System.nanoTime() - startedNanos);
    }

    public void recordElapsed(WorkerSection section, long startedNanos) {
        if (!measurementIsCurrent(startedNanos)) return;
        record(section, System.nanoTime() - startedNanos);
    }

    public void recordElapsed(WorkerSection section, AsyncToken token) {
        if (!asyncMeasurementIsCurrent(token)) return;
        record(section, System.nanoTime() - token.startedNanos());
    }

    public void add(Counter counter, long amount) {
        if (!this.enabled) return;
        synchronized (this.counters) { this.counters.merge(counter, amount, Long::sum); }
    }

    public void set(Counter counter, long value) {
        if (!this.enabled) return;
        synchronized (this.counters) { this.counters.put(counter, value); }
    }

    /** Veröffentlicht hoechstens vier Text-Snapshots pro Sekunde und verdichtet Graphen auf 20 Hz. */
    public ProfilerSnapshot publishSnapshot() {
        if (!this.enabled) return this.snapshot;
        long now = System.nanoTime();
        synchronized (this) {
            if (now - this.lastGraphNanos >= GRAPH_INTERVAL) {
                double cpu = finiteOrZero(this.pendingCpuFrame);
                double gpu = finiteOrZero(this.pendingGpuFrame);
                double tick = finiteOrZero(this.pendingTick);
                if (this.graph.size() == GRAPH_CAPACITY) this.graph.removeFirst();
                this.graph.addLast(new GraphSample(now, cpu, gpu, tick));
                this.lastGraphNanos = now;
            }
            if (now - this.lastPublishedNanos < SNAPSHOT_INTERVAL) return this.snapshot;
            this.lastPublishedNanos = now;
            this.snapshot = buildSnapshot(now);
            return this.snapshot;
        }
    }

    public ProfilerSnapshot snapshot() { return this.snapshot; }

    public void reset() {
        synchronized (this) {
            resetLocked();
            if (this.enabled) this.startedNanos = System.nanoTime();
        }
    }

    private void updatePending(CpuSection section, long nanos) {
        if (section == CpuSection.FRAME) synchronized (this) {
            this.pendingCpuFrame = nanos / 1_000_000.0;
        }
    }

    private boolean measurementIsCurrent(long startedNanos) {
        return this.enabled && startedNanos != 0 && this.beginGeneration.get() == this.generation;
    }

    private boolean asyncMeasurementIsCurrent(AsyncToken token) {
        return this.enabled && token != null && token.generation() == this.generation;
    }

    private ProfilerSnapshot buildSnapshot(long now) {
        EnumMap<Counter, Long> counterCopy;
        synchronized (this.counters) { counterCopy = new EnumMap<>(this.counters); }
        return new ProfilerSnapshot(this.generation, now, snapshot(this.cpu, now),
                snapshot(this.gpu, now), snapshot(this.tick, now), snapshot(this.l0, now),
                Collections.unmodifiableMap(counterCopy), List.copyOf(this.graph));
    }

    private void resetLocked() {
        this.generation++;
        clear(this.cpu); clear(this.gpu); clear(this.tick); clear(this.l0);
        synchronized (this.counters) { this.counters.clear(); }
        this.graph.clear();
        this.pendingCpuFrame = this.pendingGpuFrame = this.pendingTick = Double.NaN;
        this.lastPublishedNanos = this.lastGraphNanos = 0;
        this.snapshot = emptySnapshot(this.generation);
    }

    private static double finiteOrZero(double value) { return Double.isFinite(value) ? value : 0; }

    private static <E extends Enum<E>> EnumMap<E, Series> series(Class<E> type) {
        EnumMap<E, Series> result = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) result.put(value, new Series());
        return result;
    }

    private static <E extends Enum<E>> Map<E, TimingStats> snapshot(EnumMap<E, Series> source, long now) {
        Map<E, TimingStats> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value.snapshot(now)));
        return Collections.unmodifiableMap(result);
    }

    private static void clear(Map<?, Series> map) { map.values().forEach(Series::clear); }

    private static ProfilerSnapshot emptySnapshot(long generation) {
        return new ProfilerSnapshot(generation, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), List.of());
    }

    private static final class Series {
        private final long[] values = new long[STAT_CAPACITY];
        private int next;
        private int size;
        private long totalSamples;
        private long firstSampleNanos;
        private long current;

        synchronized void add(long nanos) {
            long value = Math.max(0, nanos);
            if (this.totalSamples == 0) this.firstSampleNanos = System.nanoTime();
            this.values[this.next] = value;
            this.next = (this.next + 1) % this.values.length;
            if (this.size < this.values.length) this.size++;
            this.totalSamples++;
            this.current = value;
        }

        synchronized TimingStats snapshot(long now) {
            if (this.size == 0) return TimingStats.EMPTY;
            long[] sorted = new long[this.size];
            long sum = 0, max = 0;
            for (int i = 0; i < this.size; i++) {
                long value = this.values[i];
                sorted[i] = value;
                sum += value;
                max = Math.max(max, value);
            }
            java.util.Arrays.sort(sorted);
            int p95Index = Math.max(0, (int) Math.ceil(this.size * 0.95) - 1);
            double seconds = Math.max(1.0e-9, (now - this.firstSampleNanos) / 1_000_000_000.0);
            return new TimingStats(this.current, (double) sum / this.size, sorted[p95Index], max,
                    this.totalSamples, this.totalSamples / seconds);
        }

        synchronized void clear() {
            java.util.Arrays.fill(this.values, 0);
            this.next = this.size = 0;
            this.totalSamples = this.firstSampleNanos = this.current = 0;
        }
    }
}
