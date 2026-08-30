package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.PackedQuad;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.SurfaceSample;
import de.skyengine.game.world.lod.LodVolumeRequest;
import de.skyengine.game.world.lod.LodVoxelSection;
import de.skyengine.game.world.lod.VoxelLodMesher;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                    SurfaceSample.height(generator.sampleLodSurfaces(point[0], point[1]).ground()));
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

            int groundY = SurfaceSample.height(surfaces.ground());
            assertEquals(generator.surfaceSolidHeight(point[0], point[1]), groundY);
            assertEquals(SurfaceSample.block(surfaces.ground()), chunk.getBlock(lx, groundY, lz),
                    "LOD-Bodenmaterial weicht von generate() ab");

            int surfaceY = SurfaceSample.height(surfaces.surface());
            assertEquals(SurfaceSample.block(surfaces.surface()), chunk.getBlock(lx, surfaceY, lz),
                    "LOD-Oberflaeche weicht von generate() ab");
            if (surfaceY > groundY) assertEquals(Blocks.WATER,
                    SurfaceSample.block(surfaces.surface()));
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
            assertEquals(0, SurfaceSample.height(surfaces.ground()));
            assertEquals(Blocks.BEDROCK, SurfaceSample.block(surfaces.ground()));
            assertEquals(Blocks.WATER, SurfaceSample.block(surfaces.surface()));
        }
    }

    @Test
    void chunkBulkSamplesAreBitIdenticalToEveryScalarColumn() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        int[][] chunks = {
                {Math.floorDiv(-8154, ChunkSection.SIZE), Math.floorDiv(-17295, ChunkSection.SIZE)},
                {Math.floorDiv(-8559, ChunkSection.SIZE), Math.floorDiv(-17057, ChunkSection.SIZE)},
                {Math.floorDiv(-7668, ChunkSection.SIZE), Math.floorDiv(-18064, ChunkSection.SIZE)},
                {Math.floorDiv(-6854, ChunkSection.SIZE), Math.floorDiv(-17942, ChunkSection.SIZE)}
        };
        int count = ChunkSection.SIZE * ChunkSection.SIZE;

        for (int[] position : chunks) {
            long[] ground = new long[count];
            long[] surface = new long[count];
            generator.fillLodSurfaces(position[0], position[1], ground, surface);
            int baseX = position[0] << ChunkSection.SHIFT;
            int baseZ = position[1] << ChunkSection.SHIFT;

            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) {
                    int index = z * ChunkSection.SIZE + x;
                    WorldGenerator.LodSurfaces expected =
                            generator.sampleLodSurfaces(baseX + x, baseZ + z);
                    assertEquals(expected.ground(), ground[index],
                            "Bulk-Boden @ " + (baseX + x) + "," + (baseZ + z));
                    assertEquals(expected.surface(), surface[index],
                            "Bulk-Oberflaeche @ " + (baseX + x) + "," + (baseZ + z));
                }
            }
        }
    }

    @Test
    void analyticRootProducesBroadUpwardTerrainCoverage() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(1234);
        LodVoxelSection root = new LodVoxelSection(0, 0, 0, 4,
                LodVoxelSection.Completeness.PROVISIONAL);
        generator.fillLodVolume(new LodVolumeRequest(0, 0, 0, 4), root::set);
        VoxelLodMesher.MaterialResolver materials = (state, axis, side) ->
                new VoxelLodMesher.Material(state & 0xFFFF, RenderLayer.OPAQUE, 0, 0, true);

        VoxelLodMesher.Mesh mesh = VoxelLodMesher.mesh(root, materials, null);

        assertTrue(java.util.Arrays.stream(mesh.opaque()).anyMatch(quad ->
                        PackedQuad.axis(quad) == PackedQuad.AXIS_Y && PackedQuad.positiveSide(quad)),
                "Der analytische Root braucht sichtbare Oberseiten");
    }
}
