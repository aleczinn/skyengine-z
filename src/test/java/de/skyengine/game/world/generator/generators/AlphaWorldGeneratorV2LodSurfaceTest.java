package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class AlphaWorldGeneratorV2LodSurfaceTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void lodGroundUsesTheRealThreeDimensionalSurfaceAtReportedLandCoordinates() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        int[][] points = {
                {-7610, -17973}, // +5 gegenueber der alten 2D-LOD-Hoehe
                {-7665, -18177}, // -7 gegenueber der alten 2D-LOD-Hoehe
                {-7664, -18178},
                {-7558, -18188}
        };

        for (int[] point : points) {
            int baseHeight = generator.sampleHeight(point[0], point[1]);
            int solidHeight = generator.surfaceSolidHeight(point[0], point[1]);
            assertNotEquals(baseHeight, solidHeight,
                    "Regression braucht 3D-Relief bei " + point[0] + "," + point[1]);
            assertEquals(solidHeight,
                    LodDataSource.height(generator.sampleLodSurfaces(point[0], point[1]).ground()));
        }
    }

    @Test
    void lodSamplesMatchTheBlocksWrittenByGenerate() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        int[][] points = {
                {-7610, -17973}, {-7665, -18177},
                {-8154, -17295}, {-8146, -17291}, {-8158, -17321}
        };

        for (int[] point : points) {
            int cx = Math.floorDiv(point[0], ChunkSection.SIZE);
            int cz = Math.floorDiv(point[1], ChunkSection.SIZE);
            Chunk chunk = new Chunk(cx, cz);
            generator.generate(chunk);
            int lx = Math.floorMod(point[0], ChunkSection.SIZE);
            int lz = Math.floorMod(point[1], ChunkSection.SIZE);
            WorldGenerator.LodSurfaces surfaces = generator.sampleLodSurfaces(point[0], point[1]);

            int groundY = LodDataSource.height(surfaces.ground());
            assertEquals(generator.surfaceSolidHeight(point[0], point[1]), groundY);
            assertEquals(LodDataSource.block(surfaces.ground()), chunk.getBlock(lx, groundY, lz),
                    "LOD-Bodenmaterial weicht von generate() ab");

            int surfaceY = LodDataSource.height(surfaces.surface());
            assertEquals(LodDataSource.block(surfaces.surface()), chunk.getBlock(lx, surfaceY, lz),
                    "LOD-Oberflaeche weicht von generate() ab");
            if (surfaceY > groundY) assertEquals(Blocks.WATER,
                    LodDataSource.block(surfaces.surface()));
        }
    }

    @Test
    void bedrockOnlyOceanColumnsDoNotInventAGroundBlockAtYOne() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        int chunkX = Math.floorDiv(-8559, ChunkSection.SIZE);
        int chunkZ = Math.floorDiv(-17057, ChunkSection.SIZE);
        Chunk chunk = new Chunk(chunkX, chunkZ);
        generator.generate(chunk);

        int[] worldXs = {-8576, -8559, -8550};
        for (int worldX : worldXs) {
            int localX = Math.floorMod(worldX, ChunkSection.SIZE);
            int localZ = Math.floorMod(-17057, ChunkSection.SIZE);
            assertEquals(Blocks.BEDROCK, chunk.getBlock(localX, 0, localZ));
            assertEquals(Blocks.WATER, chunk.getBlock(localX, 1, localZ),
                    "Die reproduzierte Tiefsee-Spalte muss direkt ueber Bedrock Wasser enthalten");

            assertEquals(0, generator.surfaceSolidHeight(worldX, -17057),
                    "LOD darf den Wasserblock auf Y=1 nicht als festen Boden melden");
            WorldGenerator.LodSurfaces surfaces = generator.sampleLodSurfaces(worldX, -17057);
            assertEquals(0, LodDataSource.height(surfaces.ground()));
            assertEquals(Blocks.BEDROCK, LodDataSource.block(surfaces.ground()));
            assertEquals(Blocks.WATER, LodDataSource.block(surfaces.surface()));
        }
    }
}
