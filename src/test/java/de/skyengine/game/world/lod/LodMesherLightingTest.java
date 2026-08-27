package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.chunk.VertexLight;
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
    void marksFlatFluidTopsAtNormalAndSuperregionPackingScales() {
        LodDataSource ocean = source(Blocks.WATER, 63, x -> 50);
        assertFlatFluidTopFlags(mesh(ocean, 1, 1), 63F + FluidGeometry.SOURCE_RENDER_HEIGHT);
        assertFlatFluidTopFlags(mesh(ocean, 3, 4), 63F + FluidGeometry.SOURCE_RENDER_HEIGHT);

        LodManager.LodMeshResult dry = mesh(source(Blocks.STONE, 63, x -> 63));
        for (int p = 4; p < dry.opaqueData().length; p += ChunkMesher.VERTEX_SIZE) {
            assertEquals(0, dry.opaqueData()[p] & ChunkMesher.FLAT_SOURCE_FLUID_TOP,
                    "opaque terrain must never be shader-snapped as fluid");
        }
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

    @Test
    void columnPathLightsWallsAndWorldBottomFromTheWaterEnvelope() {
        LodDataSource columns = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }

            @Override
            public LodColumn sampleColumn(int x, int z, int size) {
                int groundTop = x >= 64 ? 51 : 41;
                return new LodColumn(new long[]{
                        LodColumn.pack(Blocks.BEDROCK, 0, 1, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.STONE, 1, groundTop, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.WATER, groundTop, 64, LodColumn.FLAG_SKY_OPEN)});
            }

            @Override
            public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.WATER, 63);
            }
        };

        LodManager.LodMeshResult result = mesh(columns);

        assertEquals(List.of(0, 0, 2, 2),
                verticalQuadLights(result.opaqueData(), result.yBase(), 64F, 41F, 51F));
        assertAllEqual(0, horizontalQuadLights(result.opaqueData(), result.yBase(), 0F));
        float waterRenderY = 63F + FluidGeometry.SOURCE_HEIGHT - FluidGeometry.TOP_RENDER_EPSILON;
        assertAllEqual(15, horizontalQuadLights(result.translucentData(), result.yBase(), waterRenderY));
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
        return mesh(source, 1, 1);
    }

    private static LodManager.LodMeshResult mesh(LodDataSource source, int level, int sizeRegions) {
        GameSettings settings = GameSettings.get();
        boolean previousAo = settings.ambientOcclusion;
        GameSettings.LodAmbientOcclusionQuality previousQuality = settings.lodAmbientOcclusionQuality;
        /* Diese Tests pruefen den AO-MODUS je Level. Der haengt seit LodAmbientOcclusionQuality an einer
           Einstellung, die GameSettings.get() aus der echten options.json des Nutzers laedt —
           ohne dieses Pinnen faellt der Test um, sobald jemand im Spiel MID/HIGH waehlt. */
        settings.lodAmbientOcclusionQuality = GameSettings.LodAmbientOcclusionQuality.LOW;
        settings.ambientOcclusion = false;
        try {
            LodConfig config = LodConfig.of(16, 128);
            return new LodMesher().mesh(source, new LodBlockAppearance(), config,
                    level, sizeRegions, 0, 0, 0, 0, 64, 64);
        } finally {
            settings.ambientOcclusion = previousAo;
            settings.lodAmbientOcclusionQuality = previousQuality;
        }
    }

    private static void assertFlatFluidTopFlags(LodManager.LodMeshResult result, float expectedY) {
        int[] data = result.translucentData();
        float scale = LodMesher.posScaleFor(result.sizeRegions());
        boolean found = false;
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean matches = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float y = ((data[p] >>> 16) & 0xFFFF) / scale - 1F + result.yBase();
                if (Math.abs(y - expectedY) > 0.01F) matches = false;
            }
            if (!matches) continue;
            found = true;
            for (int v = 0; v < 4; v++) {
                int lightAndFlags = data[q + v * ChunkMesher.VERTEX_SIZE + 4];
                assertEquals(ChunkMesher.FLAT_SOURCE_FLUID_TOP,
                        lightAndFlags & ChunkMesher.FLAT_SOURCE_FLUID_TOP);
                assertEquals(15, VertexLight.sky(lightAndFlags) / 17,
                        "fluid-top skylight must be preserved");
            }
        }
        assertTrue(found, "expected a flat LOD fluid top at " + expectedY);
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
                result.add(VertexLight.sky(data[q + v * ChunkMesher.VERTEX_SIZE + 4]) / 17);
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
                float x = xzCoordinate(data[p] & 0xFFFF);
                float y = y(data[p], yBase);
                constantX &= Math.abs(x - expectedX) <= 0.01F;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (constantX && Math.abs(minY - expectedMinY) <= 0.01F
                    && Math.abs(maxY - expectedMaxY) <= 0.01F) {
                List<Integer> result = new ArrayList<>(4);
                for (int v = 0; v < 4; v++) {
                    result.add(VertexLight.sky(data[q + v * ChunkMesher.VERTEX_SIZE + 4]) / 17);
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
        return xzCoordinate(data[offset] & 0xFFFF);
    }

    private static float z(int[] data, int offset) {
        return xzCoordinate(data[offset + 1] & 0xFFFF);
    }

    private static void assertAllEqual(int expected, List<Integer> values) {
        for (int value : values) assertEquals(expected, value);
    }

    private static float y(int packedPosition, int yBase) {
        return yCoordinate((packedPosition >>> 16) & 0xFFFF) + yBase;
    }

    private static float xzCoordinate(int packed) {
        return packed / LodMesher.posScaleFor(1) - LodMesher.XZ_POSITION_BIAS;
    }

    private static float yCoordinate(int packed) {
        return packed / LodMesher.posScaleFor(1) - 1F;
    }
}
