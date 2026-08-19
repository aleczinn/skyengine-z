package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.LodFeatureBuffer;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkLodColumnsPerformanceTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void directFarLodNeverGeneratesFullChunkAndOnlyBuildsRequestedLevel() {
        CountingGenerator generator = new CountingGenerator();
        LodFeatureBuffer features = new ChunkDecorator(generator, List.of()).decorateForLod(3, -4);

        ChunkLodColumns columns = ChunkLodColumns.fromGenerator(generator, features, 3, -4, 5);

        assertEquals(0, generator.generateCalls);
        assertTrue(columns.hasLevel(5));
        assertFalse(columns.hasLevel(0));
        assertEquals(Blocks.GRASS_BLOCK, LodColumn.state(columns.get(0, 0, 32).interval(0)));
    }

    @Test
    void oneFineBuildDerivesCoarserLevelsLazilyWithoutGeneratorWork() {
        CountingGenerator generator = new CountingGenerator();
        LodFeatureBuffer features = new ChunkDecorator(generator, List.of()).decorateForLod(0, 0);

        ChunkLodColumns columns = ChunkLodColumns.fromGenerator(generator, features, 0, 0, 1);

        assertFalse(columns.hasLevel(0));
        assertTrue(columns.hasLevel(1));
        for (int level = 2; level < ChunkLodColumns.LEVELS; level++) {
            assertFalse(columns.hasLevel(level), "L" + level + " darf nicht vorab gebaut werden");
        }
        assertTrue(columns.materializeLevel(5));
        assertTrue(columns.hasLevel(5));
        assertEquals(0, generator.generateCalls);
    }

    @Test
    void adaptiveCacheUsesLargeBudgetButKeepsSmallHeapSafe() {
        assertEquals(64L << 20, PersistentLodDataSource.adaptiveCacheBytes(128L << 20));
        assertEquals(256L << 20, PersistentLodDataSource.adaptiveCacheBytes(1L << 30));
        assertEquals(512L << 20, PersistentLodDataSource.adaptiveCacheBytes(8L << 30));
    }

    @Test
    void combinedAlphaSurfaceSampleMatchesTheTwoCanonicalSamples() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(1234);
        int[][] points = {{0, 0}, {137, -91}, {-1024, 2048}, {8191, 4097}};
        for (int[] point : points) {
            WorldGenerator.LodSurfaces combined = generator.sampleLodSurfaces(point[0], point[1]);
            assertEquals(generator.sampleGroundSurface(point[0], point[1]), combined.ground());
            assertEquals(generator.sampleSurface(point[0], point[1]), combined.surface());
        }
    }

    @Test
    void generatedAlphaColumnsKeepBedrockAsSeparateBottomInterval() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(1234);
        LodFeatureBuffer features = new ChunkDecorator(generator, List.of()).decorateForLod(0, 0);

        LodColumn column = ChunkLodColumns.fromGenerator(generator, features, 0, 0, 5)
                .get(0, 0, 32);

        assertTrue(column.size() >= 2);
        assertEquals(Blocks.BEDROCK, LodColumn.state(column.interval(0)));
        assertEquals(0, LodColumn.minY(column.interval(0)));
        assertEquals(1, LodColumn.maxY(column.interval(0)));
        assertTrue(LodColumn.terrain(column.interval(0)));
    }

    @Test
    void untouchedOceanChunkHasIdenticalGeneratorAndExactLodColumns() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        int chunkX = Math.floorDiv(-8154, ChunkSection.SIZE);
        int chunkZ = Math.floorDiv(-17295, ChunkSection.SIZE);
        assertGeneratorMatchesExact(generator, chunkX, chunkZ);
    }

    @Test
    void bedrockDeepOceanChunkHasIdenticalGeneratorAndExactLodColumns() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        int chunkX = Math.floorDiv(-8559, ChunkSection.SIZE);
        int chunkZ = Math.floorDiv(-17057, ChunkSection.SIZE);
        assertGeneratorMatchesExact(generator, chunkX, chunkZ);
    }

    @Test
    void reportedLandChunkHasIdenticalGeneratorAndExactLodColumns() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        int chunkX = Math.floorDiv(-7668, ChunkSection.SIZE);
        int chunkZ = Math.floorDiv(-18064, ChunkSection.SIZE);
        assertGeneratorMatchesExact(generator, chunkX, chunkZ);
    }

    private static void assertGeneratorMatchesExact(AlphaWorldGeneratorV2 generator,
                                                    int chunkX, int chunkZ) {
        ChunkDecorator decorator = new ChunkDecorator(generator, List.of());
        Chunk exactChunk = new Chunk(chunkX, chunkZ);
        generator.generate(exactChunk);

        for (int level = 1; level <= 3; level++) {
            ChunkLodColumns generated = ChunkLodColumns.fromGenerator(generator,
                    decorator.decorateForLod(chunkX, chunkZ), chunkX, chunkZ, level);
            ChunkLodColumns exact = ChunkLodColumns.fromChunk(exactChunk, generator, level);
            int size = 1 << level;
            for (int z = 0; z < ChunkSection.SIZE; z += size) {
                for (int x = 0; x < ChunkSection.SIZE; x += size) {
                    assertColumnEquals(exact.get(x, z, size), generated.get(x, z, size),
                            "chunk " + chunkX + "," + chunkZ + " L" + level + " @ " + x + "," + z);
                }
            }
        }
    }

    @Test
    void exactProjectionConsumesSingleValueSectionsAsRuns() {
        Chunk chunk = new Chunk(0, 0);
        chunk.installSection(0, new ChunkSection(new PalettedContainer(
                ChunkSection.VOLUME, Blocks.STONE)));

        LodColumn column = ChunkLodColumns.fromChunk(chunk, 5).get(0, 0, 32);

        assertEquals(1, column.size());
        assertEquals(Blocks.STONE, LodColumn.state(column.interval(0)));
        assertEquals(0, LodColumn.minY(column.interval(0)));
        assertEquals(32, LodColumn.maxY(column.interval(0)));
        assertEquals(1024, LodColumn.coverage(column.interval(0)));
    }

    private static final class CountingGenerator extends WorldGenerator {
        private int generateCalls;

        private CountingGenerator() { super(1234); }
        @Override public int sampleHeight(int x, int z) { return 64; }
        @Override public void generate(Chunk chunk) { this.generateCalls++; }
    }

    private static void assertColumnEquals(LodColumn expected, LodColumn actual, String message) {
        assertEquals(expected.size(), actual.size(), message + " interval count");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.interval(i), actual.interval(i), message + " interval " + i);
        }
    }
}
