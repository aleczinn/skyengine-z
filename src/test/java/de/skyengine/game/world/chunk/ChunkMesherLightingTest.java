package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkMesherLightingTest {

    private static final float EPSILON = 0.0011F;

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void preservesFractionalCornerLightInTheVertexFormat() {
        int direct = VertexLight.fromLevels(15, 14);
        assertEquals(255, VertexLight.sky(direct));
        assertEquals(238, VertexLight.block(direct));

        int halfStep = VertexLight.average(58, 0, 4);
        assertEquals(247, VertexLight.sky(halfStep));
        assertFalse(VertexLight.sky(halfStep) % 17 == 0,
                "14.5 light levels must not be rounded back to a storage level");
    }

    @Test
    void sharedTopCornerUsesOneCanonicalLightValueInsideAChunk() {
        Chunk chunk = new Chunk(0, 0);
        fillFloor(chunk, 10, 13, 10, 13, 10);
        setGradient(chunk, 12, 12, 11);

        ChunkMesher.MeshData mesh = mesh(chunk, null, null);
        List<Integer> lights = CompactTerrainTestView.lightsAt(mesh, 12F, 11F, 12F);

        assertEquals(4, lights.size(), "four top faces share this internal world corner");
        assertAllEqual(lights);
    }

    @Test
    void sharedTopCornerMatchesBitExactlyAcrossAChunkBorder() {
        Chunk west = new Chunk(0, 0);
        Chunk east = new Chunk(1, 0);
        fillFloor(west, 29, 31, 10, 13, 10);
        fillFloor(east, 0, 2, 10, 13, 10);
        setGradient(west, 32, 12, 11);
        setGradient(east, 32, 12, 11);

        ChunkMesher.MeshData westMesh = mesh(west, null, east);
        ChunkMesher.MeshData eastMesh = mesh(east, west, null);
        List<Integer> westLights = CompactTerrainTestView.lightsAt(westMesh, 32F, 11F, 12F);
        List<Integer> eastLights = CompactTerrainTestView.lightsAt(eastMesh, 0F, 11F, 12F);

        assertFalse(westLights.isEmpty());
        assertFalse(eastLights.isEmpty());
        assertAllEqual(westLights);
        assertAllEqual(eastLights);
        assertEquals(westLights.getFirst(), eastLights.getFirst(),
                "the same world corner must not form a lighting seam at x=32");
    }

    @Test
    void affineCornerLightMayMergeWithoutChangingTheRenderedGradient() {
        Chunk chunk = new Chunk(0, 0);
        fillFloor(chunk, 5, 8, 8, 8, 10);
        for (int z = 7; z <= 9; z++) {
            for (int x = 3; x <= 10; x++) chunk.light.set(x, 11, z, Math.clamp(15 - x, 0, 15));
        }

        ChunkMesher.MeshData mesh = mesh(chunk, null, null);
        List<CompactTerrainTestView.Quad> top = CompactTerrainTestView.quads(mesh).stream()
                .filter(q -> q.axis() == 1 && q.positive() && near((float) q.plane(), 11F)
                        && q.minS() <= 5 && q.maxS() >= 9 && q.minT() <= 8 && q.maxT() >= 9)
                .toList();
        assertEquals(1, top.size(), "an exactly affine gradient should remain greedily mergeable");
        int previousSky = Integer.MAX_VALUE;
        for (int x = 5; x < 9; x++) {
            List<Integer> samples = CompactTerrainTestView.lightsAt(mesh, x + 0.5, 11, 8.5);
            assertFalse(samples.isEmpty());
            assertAllEqual(samples);
            int sky = VertexLight.sky(samples.getFirst());
            assertTrue(sky < previousSky, "the compact quad must retain the horizontal gradient");
            previousSky = sky;
        }
        assertTrue(mesh.stats.mergedCornerQuads() > 0);
    }

    @Test
    void diagonalRunsThroughTheBrighterVisibleCornerPair() {
        int dark = VertexLight.fromLevels(0, 0);
        int bright = VertexLight.fromLevels(15, 0);

        assertTrue(ChunkMesher.shouldFlipForSmoothLighting(null,
                new int[]{dark, bright, dark, bright}));
        assertFalse(ChunkMesher.shouldFlipForSmoothLighting(null,
                new int[]{bright, dark, bright, dark}));

        float[] ao = {1F, 0.4F, 1F, 0.4F};
        assertFalse(ChunkMesher.shouldFlipForSmoothLighting(ao,
                new int[]{bright, bright, bright, bright}),
                "AO and light must be evaluated as one visible corner score");
    }

    private static ChunkMesher.MeshData mesh(Chunk center, Chunk west, Chunk east) {
        return new ChunkMesher().mesh(center, 0, null, null, west, east, new Chunk[4]);
    }

    private static void fillFloor(Chunk chunk, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) chunk.setBlock(x, y, z, Blocks.STONE);
        }
    }

    private static void setGradient(Chunk chunk, int sourceWorldX, int sourceWorldZ, int y) {
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                int sky = Math.clamp(15 - Math.abs(worldX - sourceWorldX)
                        - Math.abs(worldZ - sourceWorldZ), 0, 15);
                int block = Math.clamp(14 - Math.abs(worldX - (sourceWorldX - 1))
                        - Math.abs(worldZ - (sourceWorldZ - 1)), 0, 14);
                chunk.light.set(x, y, z, sky);
                chunk.blockLight.set(x, y, z, block);
            }
        }
    }

    private static void assertAllEqual(List<Integer> lights) {
        int expected = lights.getFirst();
        for (int light : lights) assertEquals(expected, light);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPSILON;
    }

}
