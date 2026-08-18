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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GL-freier, reproduzierbarer Cold/Warm-Messstand fuer eine strukturhaltige L1-Region. */
public final class LodPerformanceCensus {

    private static final int SEED = 123;
    private static final int LEVEL = 1;

    public static void main(String[] args) {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        long[] cold = new long[5];
        long[] warm = new long[5];
        long[] surfaceBaseline = new long[5];
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
        }
        java.util.Arrays.sort(cold);
        java.util.Arrays.sort(warm);
        java.util.Arrays.sort(surfaceBaseline);
        System.out.printf(Locale.ROOT,
                "LOD L%d Region 128x128, Median aus %d: Surface-Baseline %.2f ms, Exact cold %.2f ms, Exact warm %.2f ms%n",
                LEVEL, cold.length, millis(surfaceBaseline[2]), millis(cold[2]), millis(warm[2]));
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0; }

    private static final class ExactRegionSource implements LodDataSource {
        private final WorldGenerator generator;
        private final Map<Long, ChunkLodColumns> chunks = new HashMap<>();

        private ExactRegionSource(WorldGenerator generator, int level) {
            this.generator = generator;
            int size = 1 << level;
            int minChunk = (-size) >> ChunkSection.SHIFT;
            int maxChunk = (LodMesher.REGION_BLOCKS + size) >> ChunkSection.SHIFT;
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

    private LodPerformanceCensus() {}
}
