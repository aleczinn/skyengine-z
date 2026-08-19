package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkMesherWaterGreedyTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void keepsOpenWaterGreedyButSplitsCellsTouchingIce() {
        Chunk chunk = new Chunk(0, 0);
        for (int z = 4; z < 12; z++) {
            for (int x = 4; x < 12; x++) chunk.setBlock(x, 1, z, Blocks.WATER);
        }
        chunk.setBlock(8, 1, 8,
                BlockRegistry.get(Identifier.of("skyengine:ice")).getDefaultState().getId());

        ChunkMesher.MeshData mesh = new ChunkMesher().mesh(chunk, 0,
                null, null, null, null, new Chunk[4]);

        float waterY = 1F + FluidGeometry.SOURCE_HEIGHT - FluidGeometry.TOP_RENDER_EPSILON;
        assertTrue(hasHorizontalTop(mesh.translucent, waterY, 7F, 8F, 8F, 9F),
                "water cell touching ice must remain independently sortable");
        assertTrue(hasFlaggedHorizontalTop(mesh.translucent, waterY, 7F, 8F, 8F, 9F),
                "independent flat source top must carry the analytic-height flag");
        assertTrue(hasWideHorizontalTop(mesh.translucent, waterY),
                "open water must remain greedily merged");
        assertTrue(hasFlaggedWideHorizontalTop(mesh.translucent, waterY),
                "greedy flat source top must carry the analytic-height flag");
    }

    @Test
    void leavesSlopedFluidGeometryUnmarked() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(8, 1, 8, Blocks.WATER);

        ChunkMesher.MeshData mesh = new ChunkMesher().mesh(chunk, 0,
                null, null, null, null, new Chunk[4]);

        for (int p = 4; p < mesh.translucent.length; p += ChunkMesher.VERTEX_SIZE) {
            assertEquals(0, mesh.translucent[p] & ChunkMesher.FLAT_SOURCE_FLUID_TOP,
                    "a sloped water surface or wall must not be shader-snapped");
        }
    }

    private static boolean hasHorizontalTop(int[] data, float y,
                                            float minX, float maxX, float minZ, float maxZ) {
        for (Bounds bounds : bounds(data)) {
            if (near(bounds.minY, y) && near(bounds.maxY, y)
                    && near(bounds.minX, minX) && near(bounds.maxX, maxX)
                    && near(bounds.minZ, minZ) && near(bounds.maxZ, maxZ)) return true;
        }
        return false;
    }

    private static boolean hasWideHorizontalTop(int[] data, float y) {
        for (Bounds bounds : bounds(data)) {
            if (near(bounds.minY, y) && near(bounds.maxY, y)
                    && (bounds.maxX - bounds.minX > 1.5F || bounds.maxZ - bounds.minZ > 1.5F)) return true;
        }
        return false;
    }

    private static boolean hasFlaggedHorizontalTop(int[] data, float y,
                                                    float minX, float maxX,
                                                    float minZ, float maxZ) {
        int stride = 4 * ChunkMesher.VERTEX_SIZE;
        java.util.List<Bounds> bounds = bounds(data);
        for (int q = 0; q < data.length; q += stride) {
            Bounds b = bounds.get(q / stride);
            if (!near(b.minY, y) || !near(b.maxY, y)
                    || !near(b.minX, minX) || !near(b.maxX, maxX)
                    || !near(b.minZ, minZ) || !near(b.maxZ, maxZ)) continue;
            return quadHasFlag(data, q);
        }
        return false;
    }

    private static boolean hasFlaggedWideHorizontalTop(int[] data, float y) {
        int stride = 4 * ChunkMesher.VERTEX_SIZE;
        java.util.List<Bounds> bounds = bounds(data);
        for (int q = 0; q < data.length; q += stride) {
            Bounds b = bounds.get(q / stride);
            if (near(b.minY, y) && near(b.maxY, y)
                    && (b.maxX - b.minX > 1.5F || b.maxZ - b.minZ > 1.5F)
                    && quadHasFlag(data, q)) return true;
        }
        return false;
    }

    private static boolean quadHasFlag(int[] data, int offset) {
        for (int v = 0; v < 4; v++) {
            int light = data[offset + v * ChunkMesher.VERTEX_SIZE + 4];
            if ((light & ChunkMesher.FLAT_SOURCE_FLUID_TOP) == 0) return false;
        }
        return true;
    }

    private static java.util.List<Bounds> bounds(int[] data) {
        java.util.List<Bounds> result = new java.util.ArrayList<>();
        int stride = 4 * ChunkMesher.VERTEX_SIZE;
        for (int q = 0; q < data.length; q += stride) {
            Bounds b = new Bounds();
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                b.include(coordinate(data[p] & 0xFFFF), coordinate(data[p] >>> 16),
                        coordinate(data[p + 1] & 0xFFFF));
            }
            result.add(b);
        }
        return result;
    }

    private static float coordinate(int packed) {
        return packed / ChunkMesher.POS_SCALE - 1F;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.0011F;
    }

    private static final class Bounds {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        void include(float x, float y, float z) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
    }
}
