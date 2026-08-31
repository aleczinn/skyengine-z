package de.skyengine.game.world.chunk.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.core.file.Files;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkMesher;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Repeatable, GL-free micro benchmark for the production section mesher. */
public final class MesherBenchmark {
    private static final int DEFAULT_WARMUPS = 10;
    private static final int DEFAULT_ITERATIONS = 30;
    private static volatile long blackhole;

    private MesherBenchmark() {}

    public static void main(String[] args) throws Exception {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        int warmups = positiveProperty("meshBench.warmups", DEFAULT_WARMUPS);
        int iterations = positiveProperty("meshBench.iterations", DEFAULT_ITERATIONS);
        Path output = Path.of(System.getProperty("meshBench.output",
                "build/reports/meshing/mesh-benchmark.json"));

        GameSettings settings = GameSettings.get();
        settings.leavesQuality = GameSettings.LeavesQuality.HIGH;

        MesherFixture.Grid generated = MesherFixture.generated();
        List<Scenario> scenarios = List.of(
                new Scenario("generated-ao", generated, allChunks(), true),
                new Scenario("generated-no-ao", generated, allChunks(), false),
                new Scenario("full-cube-best-case", MesherFixture.solidFullCube(), new int[]{4}, true),
                new Scenario("mixed-models", MesherFixture.mixedModels(), new int[]{4}, true));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("createdUtc", Instant.now().toString());
        report.put("label", System.getProperty("meshBench.label", ""));
        report.put("environment", environment());
        report.put("config", Map.of("warmups", warmups, "iterations", iterations,
                "seed", MesherFixture.SEED, "threads", 1));
        List<Map<String, Object>> results = new ArrayList<>();
        report.put("scenarios", results);

        System.out.printf(Locale.ROOT, "L0 mesh benchmark: warmups=%d iterations=%d thread=1%n",
                warmups, iterations);
        for (Scenario scenario : scenarios) {
            Map<String, Object> result = run(scenario, warmups, iterations);
            results.add(result);
            print(result);
        }
        blackhole ^= results.hashCode();

        java.nio.file.Files.createDirectories(output.toAbsolutePath().getParent());
        java.nio.file.Files.writeString(output, serializeReport(report) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.println("JSON: " + output.toAbsolutePath());
    }

    private static Map<String, Object> run(Scenario scenario, int warmups, int iterations) {
        GameSettings.get().ambientOcclusion = scenario.ambientOcclusion;
        PhaseCollector phases = new PhaseCollector();
        ChunkMesher mesher = new ChunkMesher(phases);
        for (int i = 0; i < warmups; i++) meshRound(mesher, scenario, null, null, null);

        LongList sectionTimes = new LongList(iterations * scenario.chunkIndices.length * Chunk.SECTIONS);
        LongList nonEmptyTimes = new LongList(sectionTimes.capacity());
        LongList emptyTimes = new LongList(sectionTimes.capacity());
        LongList chunkTimes = new LongList(iterations * scenario.chunkIndices.length);
        Metrics lastMetrics = null;
        Allocation allocation = Allocation.create();
        long allocatedBefore = allocation.bytes();
        GcSnapshot gcBefore = GcSnapshot.capture();
        phases.active = true;
        long benchmarkStarted = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Metrics metrics = new Metrics();
            meshRound(mesher, scenario, sectionTimes, chunkTimes,
                    new RoundState(metrics, nonEmptyTimes, emptyTimes));
            lastMetrics = metrics;
        }
        long wallElapsed = System.nanoTime() - benchmarkStarted;
        phases.active = false;
        long allocatedAfter = allocation.bytes();
        GcSnapshot gcAfter = GcSnapshot.capture();

        int sectionCalls = iterations * scenario.chunkIndices.length * Chunk.SECTIONS;
        long meshElapsed = sectionTimes.sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", scenario.name);
        result.put("ambientOcclusion", scenario.ambientOcclusion);
        result.put("chunksPerRound", scenario.chunkIndices.length);
        result.put("sectionCalls", sectionCalls);
        result.put("timing", Map.of(
                "allSections", summary(sectionTimes),
                "nonEmptySections", summary(nonEmptyTimes),
                "emptySections", summary(emptyTimes),
                "fullChunks", summary(chunkTimes),
                "meshElapsedMs", meshElapsed / 1_000_000.0,
                "wallElapsedMs", wallElapsed / 1_000_000.0,
                "sectionsPerSecond", sectionCalls * 1_000_000_000.0 / meshElapsed,
                "chunksPerSecond", chunkTimes.size * 1_000_000_000.0 / meshElapsed));
        result.put("phases", phases.report(sectionCalls));
        result.put("allocatedBytesPerSection", allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
                ? (allocatedAfter - allocatedBefore) / (double) sectionCalls : null);
        result.put("gcCollections", gcAfter.collections - gcBefore.collections);
        result.put("gcTimeMs", gcAfter.timeMillis - gcBefore.timeMillis);
        result.put("mesh", lastMetrics == null ? Map.of() : lastMetrics.report());
        return result;
    }

    private static void meshRound(ChunkMesher mesher, Scenario scenario, LongList sectionTimes,
                                  LongList chunkTimes, RoundState state) {
        Chunk[] chunks = scenario.grid.chunks();
        for (int index : scenario.chunkIndices) {
            long chunkMeshNanos = 0L;
            for (int section = 0; section < Chunk.SECTIONS; section++) {
                long started = sectionTimes == null ? 0L : System.nanoTime();
                ChunkMesher.MeshData data = mesher.mesh(chunks[index], section,
                        scenario.grid.at(index, 0, -1), scenario.grid.at(index, 0, 1),
                        scenario.grid.at(index, -1, 0), scenario.grid.at(index, 1, 0),
                        scenario.grid.diagonals(index));
                if (sectionTimes != null) {
                    long elapsed = System.nanoTime() - started;
                    chunkMeshNanos += elapsed;
                    sectionTimes.add(elapsed);
                    (data == null || data.isEmpty() ? state.emptyTimes : state.nonEmptyTimes).add(elapsed);
                    state.metrics.accept(data);
                }
                blackhole ^= hash(data);
            }
            if (chunkTimes != null) chunkTimes.add(chunkMeshNanos);
        }
    }

    private static Map<String, Object> summary(LongList values) {
        return summarizeNanos(Arrays.copyOf(values.values, values.size));
    }

    static Map<String, Object> summarizeNanos(long... values) {
        if (values.length == 0) return Map.of("samples", 0);
        long[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        long sum = 0;
        for (long value : sorted) sum += value;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", sorted.length);
        out.put("meanMs", sum / (double) sorted.length / 1_000_000.0);
        out.put("medianMs", percentile(sorted, 0.50) / 1_000_000.0);
        out.put("p95Ms", percentile(sorted, 0.95) / 1_000_000.0);
        out.put("maxMs", sorted[sorted.length - 1] / 1_000_000.0);
        return out;
    }

    static String serializeReport(Map<String, Object> report) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(report);
    }

    private static long percentile(long[] sorted, double percentile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)];
    }

    @SuppressWarnings("unchecked")
    private static void print(Map<String, Object> result) {
        Map<String, Object> timing = (Map<String, Object>) result.get("timing");
        Map<String, Object> sections = (Map<String, Object>) timing.get("nonEmptySections");
        Map<String, Object> chunks = (Map<String, Object>) timing.get("fullChunks");
        Map<String, Object> mesh = (Map<String, Object>) result.get("mesh");
        System.out.printf(Locale.ROOT,
                "%-21s section median/p95=%s/%s ms  chunk median=%s ms  %.0f sections/s%n",
                result.get("name"), number(sections.get("medianMs")), number(sections.get("p95Ms")),
                number(chunks.get("medianMs")), ((Number) timing.get("sectionsPerSecond")).doubleValue());
        System.out.printf(Locale.ROOT,
                "  compact=%s legacy=%s bytes=%s/%s greedy=%.2fx cornerMerge=%.1f%%%n",
                mesh.getOrDefault("compactQuads", 0), mesh.getOrDefault("legacyQuads", 0),
                mesh.getOrDefault("compactBytes", 0), mesh.getOrDefault("legacyBytes", 0),
                ((Number) mesh.getOrDefault("greedyCompression", 0)).doubleValue(),
                ((Number) mesh.getOrDefault("cornerMergeRate", 0)).doubleValue() * 100.0);
    }

    private static String number(Object value) {
        return value instanceof Number number ? String.format(Locale.ROOT, "%.3f", number.doubleValue()) : "-";
    }

    private static Map<String, Object> environment() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("javaVersion", System.getProperty("java.version"));
        map.put("vm", System.getProperty("java.vm.name"));
        map.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        map.put("architecture", System.getProperty("os.arch"));
        map.put("processors", runtime.availableProcessors());
        map.put("maxHeapBytes", runtime.maxMemory());
        return map;
    }

    private static int positiveProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int[] allChunks() {
        return new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};
    }

    private static long hash(ChunkMesher.MeshData data) {
        if (data == null) return 0x517cc1b727220a95L;
        long hash = 0xcbf29ce484222325L;
        hash = fnv(hash, data.opaque);
        hash = fnv(hash, data.cutout);
        hash = fnv(hash, data.translucent);
        hash = fnv(hash, data.detail);
        if (data.compactGeometry != null) for (int i = 0; i < data.compactGeometry.length; i++) {
            hash = fnv(hash, data.compactGeometry[i]);
            hash = fnv(hash, data.compactShading[i]);
        }
        return hash;
    }

    private static long fnv(long hash, int[] data) {
        if (data == null) return (hash ^ 0x9E3779B97F4A7C15L) * 0x100000001b3L;
        hash = (hash ^ data.length) * 0x100000001b3L;
        for (int value : data) hash = (hash ^ (value & 0xFFFFFFFFL)) * 0x100000001b3L;
        return hash;
    }

    private record Scenario(String name, MesherFixture.Grid grid, int[] chunkIndices,
                            boolean ambientOcclusion) {}
    private record RoundState(Metrics metrics, LongList nonEmptyTimes, LongList emptyTimes) {}

    private static final class LongList {
        private long[] values;
        private int size;
        LongList(int capacity) { this.values = new long[Math.max(1, capacity)]; }
        int capacity() { return this.values.length; }
        void add(long value) {
            if (this.size == this.values.length) this.values = Arrays.copyOf(this.values, this.size * 2);
            this.values[this.size++] = value;
        }
        long sum() {
            long sum = 0L;
            for (int i = 0; i < this.size; i++) sum += this.values[i];
            return sum;
        }
    }

    private static final class PhaseCollector implements ChunkMesher.MeshPhaseRecorder {
        private final long[] nanos = new long[ChunkMesher.MeshPhase.values().length];
        private final long[] samples = new long[ChunkMesher.MeshPhase.values().length];
        private boolean active;
        @Override public boolean enabled() { return this.active; }
        @Override public void record(ChunkMesher.MeshPhase phase, long elapsed) {
            this.nanos[phase.ordinal()] += elapsed;
            this.samples[phase.ordinal()]++;
        }
        Map<String, Object> report(int sectionCalls) {
            Map<String, Object> report = new LinkedHashMap<>();
            for (ChunkMesher.MeshPhase phase : ChunkMesher.MeshPhase.values()) {
                long total = this.nanos[phase.ordinal()];
                long count = this.samples[phase.ordinal()];
                report.put(phase.name(), Map.of("samples", count,
                        "meanMsPerSectionCall", total / (double) sectionCalls / 1_000_000.0,
                        "meanMsWhenExecuted", count == 0 ? 0 : total / (double) count / 1_000_000.0));
            }
            return report;
        }
    }

    private static final class Metrics {
        private long sections, nonEmptySections, hash;
        private long fullCubeFaces, compactQuads, standardQuads, uniformQuads, cornerQuads;
        private long cornerFaces, cornerMerged, rejectedShading, rejectedMaterial, rejectedState;
        private long overlayFallback, legacyOpaque, legacyCutout, legacyTranslucent, legacyDetail;
        private long axisAlignedLegacy, legacyBytes, standardBytes, uniformBytes, cornerBytes;

        void accept(ChunkMesher.MeshData data) {
            this.sections++;
            if (data == null || data.isEmpty()) return;
            this.nonEmptySections++;
            this.hash ^= MesherBenchmark.hash(data);
            ChunkMesher.MeshStats stats = data.stats;
            if (stats == null) return;
            this.fullCubeFaces += stats.fullCubeFacesBeforeGreedy();
            this.compactQuads += stats.fullCubeQuadsAfterGreedy();
            this.standardQuads += stats.mergedStandardQuads();
            this.uniformQuads += stats.mergedUniformQuads();
            this.cornerQuads += stats.mergedCornerQuads();
            this.cornerFaces += stats.cornerShadingFaces();
            this.cornerMerged += stats.cornerShadingFacesMerged();
            this.rejectedShading += stats.mergeRejectedByShading();
            this.rejectedMaterial += stats.mergeRejectedByMaterial();
            this.rejectedState += stats.mergeRejectedByState();
            this.overlayFallback += stats.overlayFallbackFaces();
            this.legacyOpaque += stats.legacyOpaqueQuads();
            this.legacyCutout += stats.legacyCutoutQuads();
            this.legacyTranslucent += stats.legacyTranslucentQuads();
            this.legacyDetail += stats.legacyDetailQuads();
            this.axisAlignedLegacy += stats.axisAlignedQuantizedLegacyQuads();
            this.legacyBytes += stats.legacyBytes();
            this.standardBytes += stats.standardBytes();
            this.uniformBytes += stats.uniformBytes();
            this.cornerBytes += stats.cornerBytes();
        }

        Map<String, Object> report() {
            long legacyQuads = this.legacyOpaque + this.legacyCutout + this.legacyTranslucent + this.legacyDetail;
            long compactBytes = this.standardBytes + this.uniformBytes + this.cornerBytes;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sections", this.sections);
            map.put("nonEmptySections", this.nonEmptySections);
            map.put("hash", String.format(Locale.ROOT, "%016x", this.hash));
            map.put("fullCubeFacesBeforeGreedy", this.fullCubeFaces);
            map.put("compactQuads", this.compactQuads);
            map.put("greedyCompression", ratio(this.fullCubeFaces, this.compactQuads));
            map.put("standardQuads8Byte", this.standardQuads);
            map.put("uniformQuads12Byte", this.uniformQuads);
            map.put("cornerQuads24Byte", this.cornerQuads);
            map.put("cornerShadingFaces", this.cornerFaces);
            map.put("cornerShadingFacesMerged", this.cornerMerged);
            map.put("cornerMergeRate", ratio(this.cornerMerged, this.cornerFaces));
            map.put("mergeRejectedByShading", this.rejectedShading);
            map.put("mergeRejectedByMaterial", this.rejectedMaterial);
            map.put("mergeRejectedByState", this.rejectedState);
            map.put("overlayFallbackFaces", this.overlayFallback);
            map.put("legacyQuads", legacyQuads);
            map.put("legacyOpaqueQuads", this.legacyOpaque);
            map.put("legacyCutoutQuads", this.legacyCutout);
            map.put("legacyTranslucentQuads", this.legacyTranslucent);
            map.put("legacyDetailQuads", this.legacyDetail);
            map.put("axisAlignedQuantizedLegacyQuads", this.axisAlignedLegacy);
            map.put("axisAlignedLegacyRate", ratio(this.axisAlignedLegacy, legacyQuads));
            map.put("legacyBytes", this.legacyBytes);
            map.put("compactBytes", compactBytes);
            map.put("standardBytes", this.standardBytes);
            map.put("uniformBytes", this.uniformBytes);
            map.put("cornerBytes", this.cornerBytes);
            return map;
        }

        private static double ratio(long numerator, long denominator) {
            return denominator == 0 ? 0.0 : numerator / (double) denominator;
        }
    }

    private record GcSnapshot(long collections, long timeMillis) {
        static GcSnapshot capture() {
            long collections = 0, time = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean.getCollectionCount() >= 0) collections += bean.getCollectionCount();
                if (bean.getCollectionTime() >= 0) time += bean.getCollectionTime();
            }
            return new GcSnapshot(collections, time);
        }
    }

    private static final class Allocation {
        private final com.sun.management.ThreadMXBean bean;
        private final long threadId;
        private Allocation(com.sun.management.ThreadMXBean bean) {
            this.bean = bean;
            this.threadId = Thread.currentThread().threadId();
        }
        static Allocation create() {
            if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                    || !bean.isThreadAllocatedMemorySupported()) return new Allocation(null);
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            return new Allocation(bean);
        }
        long bytes() { return this.bean == null ? -1L : this.bean.getThreadAllocatedBytes(this.threadId); }
    }
}
