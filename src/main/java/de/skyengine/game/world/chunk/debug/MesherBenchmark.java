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
    private static final int DEFAULT_DETAIL_ITERATIONS = 16;
    private static final int DEFAULT_DETAIL_SAMPLE_STRIDE = 64;
    private static volatile long blackhole;

    private MesherBenchmark() {}

    public static void main(String[] args) throws Exception {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        int warmups = positiveProperty("meshBench.warmups", DEFAULT_WARMUPS);
        int iterations = positiveProperty("meshBench.iterations", DEFAULT_ITERATIONS);
        int detailIterations = positiveProperty("meshBench.detailIterations", DEFAULT_DETAIL_ITERATIONS);
        int detailSampleStride = positiveProperty("meshBench.fullCubeSampleStride",
                DEFAULT_DETAIL_SAMPLE_STRIDE);
        ChunkMesher.VisibilityPath visibilityPath = ChunkMesher.VisibilityPath.valueOf(
                System.getProperty("meshBench.visibilityPath", "ROW_MASK")
                        .trim().toUpperCase(Locale.ROOT));
        long timerOverheadNanos = calibrateNanoTimeOverhead();
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
        report.put("schemaVersion", 3);
        report.put("createdUtc", Instant.now().toString());
        report.put("label", System.getProperty("meshBench.label", ""));
        report.put("environment", environment());
        report.put("config", Map.of("warmups", warmups, "iterations", iterations,
                "detailIterations", detailIterations, "fullCubeSampleStride", detailSampleStride,
                "timerOverheadNanos", timerOverheadNanos,
                "seed", MesherFixture.SEED, "threads", 1,
                "visibilityPath", visibilityPath.name()));
        List<Map<String, Object>> results = new ArrayList<>();
        report.put("scenarios", results);

        System.out.printf(Locale.ROOT,
                "L0 mesh benchmark: warmups=%d iterations=%d thread=1 visibility=%s%n",
                warmups, iterations, visibilityPath);
        for (Scenario scenario : scenarios) {
            Map<String, Object> result = run(scenario, warmups, iterations, detailIterations,
                    detailSampleStride, timerOverheadNanos, visibilityPath);
            results.add(result);
            print(result);
        }
        blackhole ^= results.hashCode();

        java.nio.file.Files.createDirectories(output.toAbsolutePath().getParent());
        java.nio.file.Files.writeString(output, serializeReport(report) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.println("JSON: " + output.toAbsolutePath());
    }

    private static Map<String, Object> run(Scenario scenario, int warmups, int iterations,
                                           int detailIterations, int detailSampleStride,
                                           long timerOverheadNanos,
                                           ChunkMesher.VisibilityPath visibilityPath) {
        GameSettings.get().ambientOcclusion = scenario.ambientOcclusion;
        PhaseCollector phases = new PhaseCollector();
        ChunkMesher mesher = new ChunkMesher(phases, null, visibilityPath);
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
        result.put("fullCubeDetail", runDetailed(scenario, warmups, detailIterations,
                detailSampleStride, timerOverheadNanos, phases.meanWhenExecuted(
                        ChunkMesher.MeshPhase.FULL_CUBE_GREEDY),
                lastMetrics == null ? "" : lastMetrics.hashText(),
                lastMetrics == null ? 0L : lastMetrics.cornerQuads, visibilityPath));
        return result;
    }

    private static Map<String, Object> runDetailed(Scenario scenario, int warmups, int iterations,
                                                    int sampleStride, long timerOverheadNanos,
                                                    double coarseFullCubeMeanMs,
                                                    String baselineHash,
                                                    long cornerQuads,
                                                    ChunkMesher.VisibilityPath visibilityPath) {
        DetailCollector detail = new DetailCollector(sampleStride, timerOverheadNanos);
        ChunkMesher mesher = new ChunkMesher(null, detail, visibilityPath);
        for (int i = 0; i < warmups; i++) meshRound(mesher, scenario, null, null, null);

        detail.active = true;
        detail.collectOperations = true;
        meshRound(mesher, scenario, null, null, null);
        detail.collectOperations = false;
        Metrics lastMetrics = null;
        for (int i = 0; i < iterations; i++) {
            Metrics metrics = new Metrics();
            meshRound(mesher, scenario, null, null, new RoundState(metrics, null, null));
            lastMetrics = metrics;
        }
        detail.active = false;
        String detailHash = lastMetrics == null ? "" : lastMetrics.hashText();
        return detail.report(coarseFullCubeMeanMs, baselineHash, detailHash, cornerQuads);
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
                }
                if (state != null) state.metrics.accept(data);
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
        Map<String, Object> phases = (Map<String, Object>) result.get("phases");
        Map<String, Object> fullCube = (Map<String, Object>) phases.get("FULL_CUBE_GREEDY");
        System.out.printf(Locale.ROOT,
                "%-21s section median/p95=%s/%s ms  full-cube=%s/%s ms  chunk median=%s ms  %.0f sections/s%n",
                result.get("name"), number(sections.get("medianMs")), number(sections.get("p95Ms")),
                number(fullCube.get("medianMs")), number(fullCube.get("p95Ms")),
                number(chunks.get("medianMs")), ((Number) timing.get("sectionsPerSecond")).doubleValue());
        System.out.printf(Locale.ROOT,
                "  compact=%s legacy=%s bytes=%s/%s greedy=%.2fx cornerMerge=%.1f%%%n",
                mesh.getOrDefault("compactQuads", 0), mesh.getOrDefault("legacyQuads", 0),
                mesh.getOrDefault("compactBytes", 0), mesh.getOrDefault("legacyBytes", 0),
                ((Number) mesh.getOrDefault("greedyCompression", 0)).doubleValue(),
                ((Number) mesh.getOrDefault("cornerMergeRate", 0)).doubleValue() * 100.0);
        printDetail(result);
    }

    @SuppressWarnings("unchecked")
    private static void printDetail(Map<String, Object> result) {
        Map<String, Object> detail = (Map<String, Object>) result.get("fullCubeDetail");
        if (detail == null) return;
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) detail.get("rankedPhases");
        System.out.printf(Locale.ROOT,
                "  full-cube detail: estimated=%.3f ms coarse=%.3f ms delta=%+.1f%% hash=%s%n",
                ((Number) detail.get("estimatedMsPerNonEmptySection")).doubleValue(),
                ((Number) detail.get("coarseMeanMsWhenExecuted")).doubleValue(),
                ((Number) detail.get("attributionDeltaPercent")).doubleValue(),
                Boolean.TRUE.equals(detail.get("meshHashMatches")) ? "ok" : "MISMATCH");
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            Map<String, Object> phase = ranked.get(i);
            System.out.printf(Locale.ROOT, "    %-34s %7.3f ms  %5.1f%%%n",
                    phase.get("phase"),
                    ((Number) phase.get("estimatedMsPerNonEmptySection")).doubleValue(),
                    ((Number) phase.get("sharePercent")).doubleValue());
        }
        if (Boolean.TRUE.equals(detail.get("attributionWarning"))) {
            System.out.println("    WARNING: detailed attribution differs by more than 15% from coarse timing");
        }
        System.out.println("    next: " + detail.get("recommendedNextStep"));
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

    /** Gemittelte, innerhalb einer gemessenen Zeitspanne sichtbare nanoTime-Kosten. */
    static long calibrateNanoTimeOverhead() {
        final int calls = 1_000_000;
        long sink = 0;
        for (int i = 0; i < 20_000; i++) sink ^= System.nanoTime();
        long started = System.nanoTime();
        for (int i = 0; i < calls; i++) sink ^= System.nanoTime();
        long elapsed = System.nanoTime() - started;
        blackhole ^= sink;
        return Math.max(1L, Math.round(elapsed / (double) calls));
    }

    static boolean sampledSlice(long cursor, int stride) {
        long withinSection = cursor % (6L * 32L);
        long section = cursor / (6L * 32L);
        return (withinSection + section) % stride == 0;
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
        private final LongList[] distributions = new LongList[ChunkMesher.MeshPhase.values().length];
        private boolean active;
        PhaseCollector() {
            for (int i = 0; i < this.distributions.length; i++) {
                this.distributions[i] = new LongList(1024);
            }
        }
        @Override public boolean enabled() { return this.active; }
        @Override public void record(ChunkMesher.MeshPhase phase, long elapsed) {
            int index = phase.ordinal();
            this.nanos[index] += elapsed;
            this.samples[index]++;
            this.distributions[index].add(elapsed);
        }
        Map<String, Object> report(int sectionCalls) {
            Map<String, Object> report = new LinkedHashMap<>();
            for (ChunkMesher.MeshPhase phase : ChunkMesher.MeshPhase.values()) {
                int index = phase.ordinal();
                long total = this.nanos[index];
                long count = this.samples[index];
                Map<String, Object> values = new LinkedHashMap<>(
                        summary(this.distributions[index]));
                values.put("meanMsPerSectionCall",
                        total / (double) sectionCalls / 1_000_000.0);
                values.put("meanMsWhenExecuted",
                        count == 0 ? 0 : total / (double) count / 1_000_000.0);
                report.put(phase.name(), values);
            }
            return report;
        }
        double meanWhenExecuted(ChunkMesher.MeshPhase phase) {
            long count = this.samples[phase.ordinal()];
            return count == 0 ? 0.0 : this.nanos[phase.ordinal()] / (double) count / 1_000_000.0;
        }
    }

    private static final class DetailCollector implements ChunkMesher.FullCubeProfileRecorder {
        private static final int SLICES_PER_SECTION = 6 * 32;

        private final int sampleStride;
        private final long timerOverheadNanos;
        private final long[] nanos = new long[ChunkMesher.FullCubePhase.values().length];
        private final long[] sampledOperations = new long[nanos.length];
        private final long[] spans = new long[nanos.length];
        private final long[] sectionNanos = new long[nanos.length];
        private final long[] sectionOperations = new long[nanos.length];
        private final long[] sectionSpans = new long[nanos.length];
        private final long[] sectionPhaseSamples = new long[nanos.length];
        private final long[] operations = new long[48];
        private final long[] compatibilityCellHistogram = new long[2049];
        private long sliceCursor, totalSlices, sampledSlices, sections;
        private boolean active, collectOperations;

        DetailCollector(int sampleStride, long timerOverheadNanos) {
            this.sampleStride = sampleStride;
            this.timerOverheadNanos = timerOverheadNanos;
        }

        @Override public boolean enabled() { return this.active; }

        @Override public boolean collectOperations() { return this.collectOperations; }

        @Override public boolean sampleSlice(int face, int slice) {
            if (this.collectOperations) return false;
            this.totalSlices++;
            boolean sampled = sampledSlice(this.sliceCursor, this.sampleStride);
            this.sliceCursor++;
            if (sampled) this.sampledSlices++;
            return sampled;
        }

        @Override public void record(ChunkMesher.FullCubePhase phase, long elapsed,
                                     long operations, long spans) {
            int index = phase.ordinal();
            long overhead = multiplySaturated(this.timerOverheadNanos, spans);
            this.nanos[index] += Math.max(0, elapsed - overhead);
            this.sampledOperations[index] += operations;
            this.spans[index] += spans;
        }

        @Override public void recordSection(ChunkMesher.FullCubePhase phase, long elapsed,
                                            long operations, long spans) {
            int index = phase.ordinal();
            long overhead = multiplySaturated(this.timerOverheadNanos, spans);
            this.sectionNanos[index] += Math.max(0, elapsed - overhead);
            this.sectionOperations[index] += operations;
            this.sectionSpans[index] += spans;
            this.sectionPhaseSamples[index]++;
        }

        @Override public void recordOperations(ChunkMesher.FullCubeOperations value) {
            this.sections++;
            long[] v = this.operations;
            v[0] += value.cellsScanned();
            v[1] += value.fullCubeCandidates();
            v[2] += value.faceNeighborLookups();
            v[3] += value.visibleFaces();
            v[4] += value.lightFaces();
            v[5] += value.lightSampleRequests();
            v[6] += value.lightCacheHits();
            v[7] += value.lightCacheMisses();
            v[8] += value.aoFaces();
            v[9] += value.lightOccluderLookups();
            v[10] += value.aoOccluderLookups();
            v[11] += value.maskCells();
            v[12] += value.rectangleRoots();
            v[13] += value.identityChecks();
            v[14] += value.widthExtensions();
            v[15] += value.heightExtensions();
            v[16] += value.compatibilityCalls();
            v[17] += value.compatibilityCells();
            v[18] += value.planeComparisons();
            v[19] += value.compatibilityEarlyRejects();
            v[20] += value.normalDiagonalAccepted();
            v[21] += value.flippedDiagonalAccepted();
            v[22] += value.compactQuads();
            v[23] += value.compactBytes();
            v[24] += value.overlayFallbackFaces();
            v[25] += value.sectionCellsClassified();
            v[26] += value.haloCellsClassified();
            v[27] += value.visibilityWordsProcessed();
            v[28] += value.neighborWordReads();
            v[29] += value.exceptionNeighborStateReads();
            v[30] += value.compatibilityWidthCalls();
            v[31] += value.compatibilityHeightCalls();
            v[32] += value.compatibilityFinalCalls();
            v[33] += value.acceptedBothDiagonals();
            v[34] += value.acceptedNormalOnly();
            v[35] += value.acceptedFlippedOnly();
            v[36] += value.rejectedBothDiagonals();
            v[37] += value.earlyRejectFirstCell();
            v[38] += value.earlyRejectFirstChannel();
            v[39] += value.earlyRejectFirstDiagonal();
            v[40] += value.fullyScannedSuccessfulCandidates();
            v[41] += value.fullyScannedRejectedCandidates();
            v[42] += value.singlePlaneCandidates();
            v[43] += value.singlePlaneAccepted();
            v[44] += value.incrementalBorderChecks();
            v[45] += value.incrementalBorderCellsScanned();
            v[46] += value.fullCandidateRescansAvoided();
            v[47] += value.sourceCellsAvoided();
            long[] histogram = value.compatibilityCellHistogram();
            for (int i = 0; i < Math.min(histogram.length,
                    this.compatibilityCellHistogram.length); i++) {
                this.compatibilityCellHistogram[i] += histogram[i];
            }
        }

        Map<String, Object> report(double coarseMeanMs, String baselineHash, String detailHash,
                                   long cornerQuads) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("method", "deterministic-rotating-slice-sampling");
            report.put("sampleStride", this.sampleStride);
            report.put("timerOverheadNanos", this.timerOverheadNanos);
            report.put("profiledNonEmptySections", this.sections);
            report.put("totalSlices", this.totalSlices);
            report.put("sampledSlices", this.sampledSlices);

            Map<String, Object> phases = new LinkedHashMap<>();
            List<Map<String, Object>> ranked = new ArrayList<>();
            double estimatedTotal = 0.0;
            for (ChunkMesher.FullCubePhase phase : ChunkMesher.FullCubePhase.values()) {
                int index = phase.ordinal();
                double estimatedMs = this.sectionPhaseSamples[index] == 0
                        ? estimatedMsPerSection(this.nanos[index])
                        : this.sectionNanos[index] / (double) this.sectionPhaseSamples[index] / 1_000_000.0;
                estimatedTotal += estimatedMs;
                Map<String, Object> values = new LinkedHashMap<>();
                long measuredNanos = this.sectionPhaseSamples[index] == 0
                        ? this.nanos[index] : this.sectionNanos[index];
                long measuredOperations = this.sectionPhaseSamples[index] == 0
                        ? this.sampledOperations[index] : this.sectionOperations[index];
                long measuredSpans = this.sectionPhaseSamples[index] == 0
                        ? this.spans[index] : this.sectionSpans[index];
                values.put("correctedSampledNanos", measuredNanos);
                values.put("sampledOperations", measuredOperations);
                values.put("timerSpans", measuredSpans);
                values.put("nanosPerSampledOperation", measuredOperations == 0 ? 0.0
                        : measuredNanos / (double) measuredOperations);
                values.put("estimatedMsPerNonEmptySection", estimatedMs);
                phases.put(phase.name(), values);
                Map<String, Object> rank = new LinkedHashMap<>();
                rank.put("phase", phase.name());
                rank.put("estimatedMsPerNonEmptySection", estimatedMs);
                ranked.add(rank);
            }

            double controlOverhead = Math.max(0.0, coarseMeanMs - estimatedTotal);
            if (controlOverhead > 0.0) {
                Map<String, Object> rank = new LinkedHashMap<>();
                rank.put("phase", "CONTROL_OVERHEAD");
                rank.put("estimatedMsPerNonEmptySection", controlOverhead);
                ranked.add(rank);
            }
            double denominator = estimatedTotal + controlOverhead;
            for (Map<String, Object> rank : ranked) {
                double value = ((Number) rank.get("estimatedMsPerNonEmptySection")).doubleValue();
                rank.put("sharePercent", denominator == 0.0 ? 0.0 : value * 100.0 / denominator);
            }
            ranked.sort((a, b) -> Double.compare(
                    ((Number) b.get("estimatedMsPerNonEmptySection")).doubleValue(),
                    ((Number) a.get("estimatedMsPerNonEmptySection")).doubleValue()));

            double deltaPercent = coarseMeanMs == 0.0 ? 0.0
                    : (estimatedTotal - coarseMeanMs) * 100.0 / coarseMeanMs;
            report.put("phases", phases);
            report.put("rankedPhases", ranked);
            report.put("operations", operationsReport(cornerQuads));
            report.put("estimatedMsPerNonEmptySection", estimatedTotal);
            report.put("coarseMeanMsWhenExecuted", coarseMeanMs);
            report.put("controlOverheadMsPerNonEmptySection", controlOverhead);
            report.put("attributionDeltaPercent", deltaPercent);
            report.put("attributionResidualMsPerNonEmptySection", coarseMeanMs - estimatedTotal);
            report.put("attributionWarning", Math.abs(deltaPercent) > 15.0);
            report.put("baselineMeshHash", baselineHash);
            report.put("detailMeshHash", detailHash);
            report.put("meshHashMatches", baselineHash.equals(detailHash));
            String dominant = ranked.stream().map(row -> (String) row.get("phase"))
                    .filter(phase -> !"CONTROL_OVERHEAD".equals(phase))
                    .findFirst().orElse("NONE");
            report.put("dominantMeasuredPhase", dominant);
            report.put("recommendedNextStep", recommendation(dominant));
            return report;
        }

        private static String recommendation(String phase) {
            return switch (phase) {
                case "STATE_CLASSIFICATION", "VISIBILITY_WORD_DERIVATION",
                        "BLOCK_FACE_VISIBILITY", "NEIGHBOR_LOOKUPS" ->
                        "inspect remaining scalar visibility work before adding complexity";
                case "LIGHT_SAMPLING", "CORNER_AO_SAMPLING" ->
                        "benchmark a full-cube-specialized light/AO sampler";
                case "GREEDY_RECTANGLE_SEARCH" ->
                        "benchmark run-length or precomputed mask traversal";
                case "SHADING_DIAGONAL_COMPATIBILITY" ->
                        "benchmark incremental or cached plane compatibility";
                case "PACKED_QUAD_EMISSION", "OVERLAY_FALLBACK_EMISSION" ->
                        "benchmark the corresponding compact or overlay write path";
                default -> "collect a longer detailed run before selecting an optimization";
            };
        }

        private double estimatedMsPerSection(long sampledNanos) {
            if (this.sampledSlices == 0) return 0.0;
            return sampledNanos / (double) this.sampledSlices * SLICES_PER_SECTION / 1_000_000.0;
        }

        private Map<String, Object> operationsReport(long cornerQuads) {
            String[] names = {
                    "cellsScanned", "fullCubeCandidates", "faceNeighborLookups", "visibleFaces",
                    "lightFaces", "lightSampleRequests", "lightCacheHits", "lightCacheMisses",
                    "aoFaces", "lightOccluderLookups", "aoOccluderLookups", "maskCells",
                    "rectangleRoots", "identityChecks", "widthExtensions", "heightExtensions",
                    "compatibilityCalls", "compatibilityCells", "planeComparisons",
                    "compatibilityEarlyRejects", "normalDiagonalAccepted",
                    "flippedDiagonalAccepted", "compactQuads", "compactBytes",
                    "overlayFallbackFaces", "sectionCellsClassified", "haloCellsClassified",
                    "visibilityWordsProcessed", "neighborWordReads",
                    "exceptionNeighborStateReads", "compatibilityWidthCalls",
                    "compatibilityHeightCalls", "compatibilityFinalCalls",
                    "acceptedBothDiagonals", "acceptedNormalOnly", "acceptedFlippedOnly",
                    "rejectedBothDiagonals", "earlyRejectFirstCell",
                    "earlyRejectFirstChannel", "earlyRejectFirstDiagonal",
                    "fullyScannedSuccessfulCandidates", "fullyScannedRejectedCandidates",
                    "singlePlaneCandidates", "singlePlaneAccepted",
                    "incrementalBorderChecks", "incrementalBorderCellsScanned",
                    "fullCandidateRescansAvoided", "sourceCellsAvoided"
            };
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < names.length; i++) {
                result.put(names[i], Map.of("total", this.operations[i],
                        "meanPerNonEmptySection", this.sections == 0 ? 0.0
                                : this.operations[i] / (double) this.sections));
            }
            long calls = this.operations[16];
            long cumulative = 0L;
            long p95Target = Math.max(1L, (long) Math.ceil(calls * 0.95));
            int p95 = 0, max = 0;
            for (int i = 0; i < this.compatibilityCellHistogram.length; i++) {
                long count = this.compatibilityCellHistogram[i];
                if (count == 0) continue;
                max = i;
                cumulative += count;
                if (p95 == 0 && cumulative >= p95Target) p95 = i;
            }
            result.put("compatibilityCellsPerCall", Map.of(
                    "mean", calls == 0 ? 0.0 : this.operations[17] / (double) calls,
                    "p95", p95, "max", max));
            result.put("compatibilityPerCornerQuad", Map.of(
                    "calls", cornerQuads == 0 ? 0.0 : calls / (double) cornerQuads,
                    "sourceCells", cornerQuads == 0 ? 0.0
                            : this.operations[17] / (double) cornerQuads));
            return result;
        }

        private static long multiplySaturated(long a, long b) {
            if (a == 0 || b == 0) return 0;
            if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
            return a * b;
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

        String hashText() {
            return String.format(Locale.ROOT, "%016x", this.hash);
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
