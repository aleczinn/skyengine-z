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
}
