package de.skyengine.game.world.lod;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.LodFeatureBuffer;
import de.skyengine.game.world.generator.feature.trees.BiomeTreeFeature;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GL-freier, reproduzierbarer Cold/Warm-Messstand fuer eine strukturhaltige L1-Region. */
public final class LodPerformanceCensus {

    private static final int SEED = 123;
    private static final int LEVEL = 1;

    public static void main(String[] args) throws Exception {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        long[] cold = new long[5];
        long[] warm = new long[5];
        long[] surfaceBaseline = new long[5];
        long[] exactMigration = new long[5];
        long[] diskWarm = new long[5];
        LodBlockAppearance appearance = new LodBlockAppearance();
        LodConfig config = LodConfig.of(16, 128);

        for (int run = 0; run < cold.length; run++) {
            WorldGenerator generator = new AlphaWorldGeneratorV2(SEED);
            long started = System.nanoTime();
            ExactRegionSource source = new ExactRegionSource(generator, LEVEL);
            LodMesher mesher = new LodMesher();
            mesher.mesh(source, appearance, config, LEVEL, 1, 0, 0, run, 0, 64, 64);
            cold[run] = System.nanoTime() - started;

            started = System.nanoTime();
            mesher.mesh(source, appearance, config, LEVEL, 1, 0, 0, run, 0, 64, 64);
            warm[run] = System.nanoTime() - started;

            started = System.nanoTime();
            new LodMesher().mesh(new GeneratorLodDataSource(generator), appearance, config,
                    LEVEL, 1, 0, 0, run, 0, 64, 64);
            surfaceBaseline[run] = System.nanoTime() - started;

            exactMigration[run] = measureExactMigration(generator, appearance, config, run);
            diskWarm[run] = measureDiskWarm(source, appearance, config, run);
        }
        java.util.Arrays.sort(cold);
        java.util.Arrays.sort(warm);
        java.util.Arrays.sort(surfaceBaseline);
        java.util.Arrays.sort(exactMigration);
        java.util.Arrays.sort(diskWarm);
        System.out.printf(Locale.ROOT,
                "LOD L%d Region 128x128, Median aus %d: Surface-Baseline %.2f ms, Exact cold %.2f ms, Exact warm %.2f ms%n",
                LEVEL, cold.length, millis(surfaceBaseline[2]), millis(cold[2]), millis(warm[2]));
        System.out.printf(Locale.ROOT,
                "LOD12-Migration: Save/Live-Neuaufbau median/p95 %.2f/%.2f ms, "
                        + "warmer Disk-Cache median/p95 %.2f/%.2f ms, "
                        + "Generator-Neuaufbau median/p95 %.2f/%.2f ms%n",
                millis(exactMigration[2]), millis(exactMigration[4]),
                millis(diskWarm[2]), millis(diskWarm[4]),
                millis(cold[2]), millis(cold[4]));
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0; }

    /** Simuliert den ersten Wiederbesuch eines gespeicherten Chunks nach Versionswechsel. */
    private static long measureExactMigration(WorldGenerator generator, LodBlockAppearance appearance,
                                              LodConfig config, int epoch) {
        int[] bounds = chunkBounds(LEVEL);
        ChunkDecorator decorator = new ChunkDecorator(generator, List.of(new BiomeTreeFeature()));
        ArrayList<Chunk> chunks = new ArrayList<>();
        for (int cz = bounds[0]; cz <= bounds[1]; cz++) {
            for (int cx = bounds[0]; cx <= bounds[1]; cx++) {
                Chunk chunk = new Chunk(cx, cz);
                generator.generate(chunk);
                decorator.decorate(chunk);
                chunks.add(chunk);
            }
        }

        long started = System.nanoTime();
        Map<Long, ChunkLodColumns> rebuilt = new HashMap<>();
        for (Chunk chunk : chunks) {
            rebuilt.put(Chunk.key(chunk.chunkX, chunk.chunkZ),
                    ChunkLodColumns.fromChunk(chunk, generator, LEVEL));
        }
        new LodMesher().mesh(new CachedRegionSource(generator, rebuilt), appearance, config,
                LEVEL, 1, 0, 0, epoch, 0, 64, 64);
        return System.nanoTime() - started;
    }

    /** Misst den zweiten Besuch nach dem einmaligen LOD12-Neuaufbau inklusive Disk-Lesen. */
    private static long measureDiskWarm(ExactRegionSource source, LodBlockAppearance appearance,
                                        LodConfig config, int epoch) throws IOException {
        Path directory = java.nio.file.Files.createTempDirectory("skyengine-lod10-census-");
        try {
            try (LodCacheStore store = new LodCacheStore(directory.toFile(), 1, 1)) {
                for (Map.Entry<Long, ChunkLodColumns> entry : source.chunks.entrySet()) {
                    store.writeLater((int) (entry.getKey() >> 32), (int) (long) entry.getKey(),
                            entry.getValue());
                }
            }

            long started = System.nanoTime();
            Map<Long, ChunkLodColumns> loaded = new HashMap<>();
            long elapsed;
            try (LodCacheStore store = new LodCacheStore(directory.toFile(), 1, 1)) {
                for (long key : source.chunks.keySet()) {
                    ChunkLodColumns columns = store.read((int) (key >> 32), (int) key);
                    if (columns == null) throw new IllegalStateException("LOD12-Warmcache fehlt");
                    loaded.put(key, columns);
                }
                new LodMesher().mesh(new CachedRegionSource(source.generator, loaded), appearance, config,
                        LEVEL, 1, 0, 0, epoch, 0, 64, 64);
                /* close() wartet auf den 100-ms-Writer-Poll. Das ist Welt-Shutdown und darf
                   nicht als Ladezeit eines warmen Regionsbesuchs missverstanden werden. */
                elapsed = System.nanoTime() - started;
            }
            return elapsed;
        } finally {
            deleteTree(directory);
        }
    }

    private static int[] chunkBounds(int level) {
        int size = 1 << level;
        return new int[]{(-size) >> ChunkSection.SHIFT,
                (LodMesher.REGION_BLOCKS + size) >> ChunkSection.SHIFT};
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = java.nio.file.Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    private static final class ExactRegionSource implements LodDataSource {
        private final WorldGenerator generator;
        private final Map<Long, ChunkLodColumns> chunks = new HashMap<>();

        private ExactRegionSource(WorldGenerator generator, int level) {
            this.generator = generator;
            int[] bounds = chunkBounds(level);
            int minChunk = bounds[0];
            int maxChunk = bounds[1];
            int width = maxChunk - minChunk + 1;
            ChunkDecorator decorator = new ChunkDecorator(generator, List.of(new BiomeTreeFeature()));
            ChunkDecorator.LodRegionFeatures region = decorator.lodRegion(minChunk, minChunk, width, width);
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                for (int cx = minChunk; cx <= maxChunk; cx++) {
                    LodFeatureBuffer features = region.forChunk(cx, cz);
                    this.chunks.put(Chunk.key(cx, cz),
                            ChunkLodColumns.fromGenerator(generator, features, cx, cz, level));
                }
            }
        }

        @Override public boolean hasColumns() { return true; }
        @Override public LodColumn sampleColumn(int x, int z, int size) {
            ChunkLodColumns columns = this.chunks.get(Chunk.key(
                    x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT));
            return columns.get(x & ChunkSection.MASK, z & ChunkSection.MASK, size);
        }
        @Override public long sampleSurface(int x, int z, int size) {
            LodColumn column = this.sampleColumn(x, z, size);
            if (column.size() == 0) return LodDataSource.pack(Blocks.AIR, 0);
            long top = column.interval(column.size() - 1);
            return LodDataSource.pack(LodColumn.state(top), LodColumn.maxY(top) - 1);
        }
        @Override public int grassTintAt(int x, int z) { return this.generator.grassTintAt(x, z); }
        @Override public int foliageTintAt(int x, int z) { return this.generator.foliageTintAt(x, z); }
    }

    private static final class CachedRegionSource implements LodDataSource {
        private final WorldGenerator generator;
        private final Map<Long, ChunkLodColumns> chunks;

        private CachedRegionSource(WorldGenerator generator, Map<Long, ChunkLodColumns> chunks) {
            this.generator = generator;
            this.chunks = chunks;
        }

        @Override public boolean hasColumns() { return true; }
        @Override public LodColumn sampleColumn(int x, int z, int size) {
            ChunkLodColumns columns = this.chunks.get(Chunk.key(
                    x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT));
            if (columns == null) return LodColumn.EMPTY;
            return columns.get(x & ChunkSection.MASK, z & ChunkSection.MASK, size);
        }
        @Override public long sampleSurface(int x, int z, int size) {
            LodColumn column = this.sampleColumn(x, z, size);
            if (column.size() == 0) return LodDataSource.pack(Blocks.AIR, 0);
            long top = column.interval(column.size() - 1);
            return LodDataSource.pack(LodColumn.state(top), LodColumn.maxY(top) - 1);
        }
        @Override public int grassTintAt(int x, int z) { return this.generator.grassTintAt(x, z); }
        @Override public int foliageTintAt(int x, int z) { return this.generator.foliageTintAt(x, z); }
    }

    private LodPerformanceCensus() {}
}
