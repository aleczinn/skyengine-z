package de.skyengine.tools.network;

import de.skyengine.core.resource.Resources;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.DimensionManager;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.effect.WorldSoundSink;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.graphics.PerformanceProfiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Headless performance reference for the former direct terrain-to-mesh path.
 * It is deliberately not reachable from production gameplay.
 */
public final class DirectChunkPipelineReference {
    private static final int DIRECT_DEPENDENCY_RINGS = 5;

    private DirectChunkPipelineReference() { }

    public static void main(String[] args) throws Exception {
        int viewDistance = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        int seed = args.length > 1 ? Integer.parseInt(args[1]) : 123456789;
        Path report = args.length > 2 && !args[2].isBlank() ? Path.of(args[2]) : null;
        int timeoutSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 60;
        int workerThreads = args.length > 4 ? Integer.parseInt(args[4])
                : Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        if (viewDistance < 2 || viewDistance > 32 || timeoutSeconds < 1
                || timeoutSeconds > 600 || workerThreads < 1 || workerThreads > 256) {
            throw new IllegalArgumentException("Usage: DirectChunkPipelineReference "
                    + "[viewDistance 2..32] [seed] [report] [timeoutSeconds 1..600] "
                    + "[workerThreads 1..256]");
        }

        bootstrapGameplay();
        PerformanceProfiler profiler = PerformanceProfiler.get();
        profiler.setEnabled(true);
        Path root = Files.createTempDirectory("skyengine-direct-reference-");
        WorldWorkerPool workers = new WorldWorkerPool(workerThreads);
        long started = System.nanoTime();
        long firstPresented = 0, collisionReady = 0, fullView = 0;
        int presented = 0, resident = 0;
        try {
            WorldSaves.WorldSave save = WorldSaves.openOrCreate(root.toFile(), "reference", seed);
            World world = new World(save, root.toFile(), WorldSoundSink.NONE, true, workers);
            try {
                try (DimensionManager.DimensionTicket ticket = world.acquireDimension(
                         WorldgenRegistries.OVERWORLD, DimensionManager.TicketType.PLAYER,
                         "direct-reference")) {
                Dimension dimension = ticket.dimension();
                EntityPlayer player = world.players().localPlayer();
                player.setDimensionId(WorldgenRegistries.OVERWORLD);
                player.setPosition(0.5, dimension.getGenerator().sampleHeight(0, 0) + 2, 0.5);
                ChunkManager chunks = dimension.getChunkManager();
                /* A visible mesh needs LIT neighbours; those need DECORATED neighbours; those
                   need GENERATED neighbours. Five radial rings cover that three-stage square
                   dilation completely. The otherwise hidden legacy overdraw is reported. */
                chunks.setRenderDistance(viewDistance + DIRECT_DEPENDENCY_RINGS);
                long renderGeneration = chunks.attachRenderer();
                profiler.reset();
                started = System.nanoTime();
                long deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds);
                long nextTick = started;
                long nextCensus = started;
                int expected = visibleChunkCount(viewDistance);
                try {
                    while (System.nanoTime() < deadline && fullView == 0) {
                        long now = System.nanoTime();
                        if (now >= nextTick) {
                            dimension.update(player);
                            world.tickLifecycle();
                            nextTick += TimeUnit.MILLISECONDS.toNanos(50);
                            if (now - nextTick > TimeUnit.MILLISECONDS.toNanos(250)) nextTick = now;
                        }
                        chunks.processRemeshes();
                        drainUploads(chunks, renderGeneration);
                        if (now >= nextCensus) {
                            presented = presentedVisible(chunks, viewDistance);
                            resident = chunks.loadedChunks().size();
                            if (presented > 0 && firstPresented == 0) firstPresented = now;
                            if (collisionReady == 0 && collisionReady(chunks)) collisionReady = now;
                            if (presented == expected) fullView = now;
                            nextCensus = now + TimeUnit.MILLISECONDS.toNanos(10);
                        }
                        Thread.sleep(1);
                    }
                } finally {
                    chunks.awaitWorkerTasks();
                    drainUploads(chunks, renderGeneration);
                    presented = presentedVisible(chunks, viewDistance);
                    resident = chunks.loadedChunks().size();
                    chunks.detachRenderer(renderGeneration);
                }
                if (fullView == 0 && presented == expected) fullView = System.nanoTime();
                profiler.publishSnapshot();
                writeReport(report, viewDistance, seed, workerThreads, expected, presented, resident,
                        started, firstPresented, collisionReady, fullView, profiler.snapshot());
                System.out.printf(Locale.ROOT,
                        "Direct reference RD%d: %d/%d presented, %d resident, workers %d, "
                                + "first/collision/full %.1f/%.1f/%.1f ms%n",
                        viewDistance, presented, expected, resident, workerThreads,
                        elapsedMillis(started, firstPresented), elapsedMillis(started, collisionReady),
                        elapsedMillis(started, fullView));
                if (presented != expected) {
                    throw new IllegalStateException("Direct reference timed out with " + presented
                            + "/" + expected + " visible columns presented");
                }
                }
            } finally {
                world.dispose();
            }
        } finally {
            workers.dispose();
            deleteTree(root);
            profiler.setEnabled(false);
        }
    }

    private static void bootstrapGameplay() {
        Resources.initialize();
        if (!BlockRegistry.isBaked()) {
            Blocks.bootstrap(Resources.defaultGameRoot().resolve("blocks").toFile());
            WorldgenRegistries.bootstrap();
        }
    }

    private static void drainUploads(ChunkManager chunks, long generation) {
        drain(chunks, chunks.getPlayerUploadQueue(), generation);
        drain(chunks, chunks.getPriorityUploadQueue(), generation);
        drain(chunks, chunks.getUploadQueue(), generation);
    }

    private static void drain(ChunkManager manager, java.util.Queue<ChunkManager.MeshBatch> queue,
                              long generation) {
        ChunkManager.MeshBatch batch;
        while ((batch = queue.poll()) != null) {
            for (ChunkManager.MeshResult result : batch.results()) {
                Chunk chunk = manager.getChunk(result.chunkX(), result.chunkZ());
                if (chunk != null && chunk.tryApplyMeshSection(generation, result.sectionY(),
                        result.meshSeq())) {
                    chunk.markSectionUploaded(generation, result.sectionY());
                }
            }
        }
    }

    private static int presentedVisible(ChunkManager chunks, int radius) {
        int result = 0;
        int squared = radius * radius;
        for (Chunk chunk : chunks.loadedChunks()) {
            if (chunk.chunkX * chunk.chunkX + chunk.chunkZ * chunk.chunkZ <= squared
                    && chunk.status == ChunkStatus.READY && chunk.isFullyUploaded()) result++;
        }
        return result;
    }

    private static boolean collisionReady(ChunkManager chunks) {
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                Chunk chunk = chunks.getChunk(x, z);
                if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return false;
            }
        }
        return true;
    }

    private static int visibleChunkCount(int radius) {
        int result = 0, squared = radius * radius;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) if (x * x + z * z <= squared) result++;
        }
        return result;
    }

    private static void writeReport(Path report, int viewDistance, int seed, int workers,
                                    int expected, int presented, int resident, long started,
                                    long first, long collision, long full,
                                    PerformanceProfiler.ProfilerSnapshot snapshot) throws Exception {
        if (report == null) return;
        Path parent = report.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        List<String> phases = new ArrayList<>();
        for (PerformanceProfiler.WorkerSection phase : PerformanceProfiler.WorkerSection.values()) {
            PerformanceProfiler.TimingStats stats = snapshot.l0().getOrDefault(
                    phase, PerformanceProfiler.TimingStats.EMPTY);
            phases.add(String.format(Locale.ROOT,
                    "\"%s\":{\"meanMillis\":%.6f,\"p95Millis\":%.6f,"
                            + "\"maxMillis\":%.6f,\"samples\":%d,\"jobsPerSecond\":%.6f}",
                    phase, stats.meanMillis(), stats.p95Millis(), stats.maxMillis(),
                    stats.samples(), stats.jobsPerSecond()));
        }
        String json = String.format(Locale.ROOT,
                "{\n  \"mode\":\"cleanup-direct-reference\",\n"
                        + "  \"seed\":%d,\n  \"viewDistance\":%d,\n"
                        + "  \"dependencyRadius\":%d,\n  \"workers\":%d,\n"
                        + "  \"expectedVisibleChunks\":%d,\n  \"presentedVisibleChunks\":%d,\n"
                        + "  \"residentChunks\":%d,\n  \"timeToFirstPresentedMillis\":%.6f,\n"
                        + "  \"timeToCollisionReadyMillis\":%.6f,\n"
                        + "  \"timeToFullViewDistanceMillis\":%.6f,\n"
                        + "  \"pipelinePhases\":{%s}\n}\n",
                seed, viewDistance, viewDistance + DIRECT_DEPENDENCY_RINGS, workers,
                expected, presented, resident,
                elapsedMillis(started, first), elapsedMillis(started, collision),
                elapsedMillis(started, full), String.join(",", phases));
        Files.writeString(report, json);
    }

    private static double elapsedMillis(long started, long finished) {
        return finished == 0 ? -1 : (finished - started) / 1_000_000.0;
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
