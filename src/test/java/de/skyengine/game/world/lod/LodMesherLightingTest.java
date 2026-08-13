package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodMesherLightingTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void keepsDryTerrainAndWaterSurfaceBrightButDarkensSeaFloor() {
        LodManager.LodMeshResult dry = mesh(source(Blocks.STONE, 50, x -> 50));
        assertAllEqual(15, horizontalQuadLights(dry.opaqueData(), dry.yBase(), 51F));

        LodManager.LodMeshResult ocean = mesh(source(Blocks.WATER, 63, x -> 50));
        assertAllEqual(2, horizontalQuadLights(ocean.opaqueData(), ocean.yBase(), 51F));
        float waterRenderY = 63F + FluidGeometry.SOURCE_HEIGHT - FluidGeometry.TOP_RENDER_EPSILON;
        assertAllEqual(15, horizontalQuadLights(ocean.translucentData(), ocean.yBase(), waterRenderY));
        List<Float> windings = horizontalQuadWindings(ocean.translucentData(), ocean.yBase(), waterRenderY);
        assertTrue(windings.stream().anyMatch(value -> value > 0F));
        assertTrue(windings.stream().anyMatch(value -> value < 0F));

        LodManager.LodMeshResult deepOcean = mesh(source(Blocks.WATER, 80, x -> 50));
        assertAllEqual(0, horizontalQuadLights(deepOcean.opaqueData(), deepOcean.yBase(), 51F));
    }

    @Test
    void interpolatesSkylightAlongUnderwaterTerrainWalls() {
        LodManager.LodMeshResult ocean = mesh(source(Blocks.WATER, 63, x -> x >= 64 ? 50 : 40));
        List<Integer> lights = verticalQuadLights(ocean.opaqueData(), ocean.yBase(), 64F, 41F, 51F);

        assertFalse(lights.isEmpty(), "Die interne Unterwasserwand muss im Mesh vorhanden sein");
        assertEquals(List.of(0, 0, 2, 2), lights);
    }

    @Test
    void neighboringWaterDarkensTheSubmergedPartOfAShoreWall() {
        LodDataSource shore = new LodDataSource() {
            @Override
            public long sampleSurface(int x, int z, int size) {
                return x >= 64 ? LodDataSource.pack(Blocks.STONE, 70)
                        : LodDataSource.pack(Blocks.WATER, 63);
            }

            @Override
            public long sampleGround(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, x >= 64 ? 70 : 50);
            }
        };

        LodManager.LodMeshResult result = mesh(shore);
        assertEquals(List.of(2, 2, 15, 15),
                verticalQuadLights(result.opaqueData(), result.yBase(), 64F, 51F, 71F));
    }

    private static LodDataSource source(int surfaceBlock, int surfaceHeight,
                                        IntUnaryOperator groundHeight) {
        return new LodDataSource() {
            @Override
            public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(surfaceBlock, surfaceHeight);
            }

            @Override
            public long sampleGround(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, groundHeight.applyAsInt(x));
            }
        };
    }

    private static LodManager.LodMeshResult mesh(LodDataSource source) {
        GameSettings settings = GameSettings.get();
        boolean previousAo = settings.ambientOcclusion;
        settings.ambientOcclusion = false;
        try {
            LodConfig config = LodConfig.of(16, 128);
            return new LodMesher().mesh(source, new LodBlockAppearance(), config,
                    1, 1, 0, 0, 0, 0, 64, 64);
        } finally {
            settings.ambientOcclusion = previousAo;
        }
    }

    private static List<Integer> horizontalQuadLights(int[] data, int yBase, float expectedY) {
        List<Integer> result = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean matches = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                if (Math.abs(y(data[p], yBase) - expectedY) > 0.01F) matches = false;
            }
            if (!matches) continue;
            for (int v = 0; v < 4; v++) {
                result.add(data[q + v * ChunkMesher.VERTEX_SIZE + 4] & 0xF);
            }
        }
        assertFalse(result.isEmpty(), "Erwartete horizontale LOD-Fläche fehlt");
        return result;
    }

    private static List<Integer> verticalQuadLights(int[] data, int yBase, float expectedX,
                                                     float expectedMinY, float expectedMaxY) {
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float minY = Float.POSITIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            boolean constantX = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float x = coordinate(data[p] & 0xFFFF);
                float y = y(data[p], yBase);
                constantX &= Math.abs(x - expectedX) <= 0.01F;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (constantX && Math.abs(minY - expectedMinY) <= 0.01F
                    && Math.abs(maxY - expectedMaxY) <= 0.01F) {
                List<Integer> result = new ArrayList<>(4);
                for (int v = 0; v < 4; v++) {
                    result.add(data[q + v * ChunkMesher.VERTEX_SIZE + 4] & 0xF);
                }
                return result;
            }
        }
        return List.of();
    }

    private static List<Float> horizontalQuadWindings(int[] data, int yBase, float expectedY) {
        List<Float> result = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean horizontal = true;
            for (int v = 0; v < 4; v++) {
                if (Math.abs(y(data[q + v * ChunkMesher.VERTEX_SIZE], yBase) - expectedY) > 0.01F) {
                    horizontal = false;
                }
            }
            if (!horizontal) continue;
            float ax = x(data, q), az = z(data, q);
            float bx = x(data, q + ChunkMesher.VERTEX_SIZE), bz = z(data, q + ChunkMesher.VERTEX_SIZE);
            float cx = x(data, q + 2 * ChunkMesher.VERTEX_SIZE), cz = z(data, q + 2 * ChunkMesher.VERTEX_SIZE);
            result.add((bz - az) * (cx - ax) - (bx - ax) * (cz - az));
        }
        return result;
    }

    private static float x(int[] data, int offset) {
        return coordinate(data[offset] & 0xFFFF);
    }

    private static float z(int[] data, int offset) {
        return coordinate(data[offset + 1] & 0xFFFF);
    }

    private static void assertAllEqual(int expected, List<Integer> values) {
        for (int value : values) assertEquals(expected, value);
    }

    private static float y(int packedPosition, int yBase) {
        return coordinate((packedPosition >>> 16) & 0xFFFF) + yBase;
    }

    private static float coordinate(int packed) {
        return packed / LodMesher.posScaleFor(1) - 1F;
    }
}
