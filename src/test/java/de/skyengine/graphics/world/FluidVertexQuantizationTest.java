package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.lod.LodMesher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluidVertexQuantizationTest {

    @Test
    void analyticFlatFluidHeightIsIdenticalAcrossEveryPositionScale() {
        float[] scales = {ChunkMesher.POS_SCALE, LodMesher.posScaleFor(1), LodMesher.posScaleFor(4)};
        int[] worldBlockY = {0, 31, 63, 255, 511};

        for (int worldY : worldBlockY) {
            float l0Raw = decodedWorldY(worldY, sectionOrigin(worldY), scales[0]);
            float lodRaw = decodedWorldY(worldY, 0, scales[1]);
            assertNotEquals(l0Raw, lodRaw,
                    "the regression requires different raw fixed-point results at y=" + worldY);

            float expected = worldY + FluidGeometry.SOURCE_RENDER_HEIGHT;
            for (float scale : scales) {
                int origin = scale == ChunkMesher.POS_SCALE ? sectionOrigin(worldY) : 0;
                float resolved = shaderResolvedWorldY(worldY, origin, scale,
                        ChunkMesher.FLAT_SOURCE_FLUID_TOP);
                assertEquals(expected, resolved, 0.000001F,
                        "flat fluid height differs for packing scale " + scale);
            }
        }
    }

    @Test
    void unmarkedFractionalGeometryKeepsItsPackedHeight() {
        float scale = LodMesher.posScaleFor(1);
        int worldY = 63;
        float raw = decodedWorldY(worldY, 0, scale);

        assertEquals(raw, shaderResolvedWorldY(worldY, 0, scale, 0), 0F);
    }

    @Test
    void integerVerticalBoundaryCoordinatesRemainExactAtEveryScale() {
        float[] scales = {ChunkMesher.POS_SCALE, LodMesher.posScaleFor(1), LodMesher.posScaleFor(4)};
        for (float scale : scales) {
            for (int coordinate : new int[]{0, 16, 32, 128, 512}) {
                int packed = Math.round((coordinate + 1F) * scale);
                assertEquals(coordinate, packed / scale - 1F, 0F,
                        "integer boundary shifted at packing scale " + scale);
            }
        }
    }

    @Test
    void lodHorizontalBiasPreservesNegativeSafetyCapCoordinates() {
        float[] scales = {LodMesher.posScaleFor(1), LodMesher.posScaleFor(4)};
        int[][] coordinates = {{-32, -4, 0, 128, 160}, {-32, -4, 0, 128, 512, 544}};
        for (int i = 0; i < scales.length; i++) {
            float scale = scales[i];
            for (int coordinate : coordinates[i]) {
                int packed = Math.round((coordinate + LodMesher.XZ_POSITION_BIAS) * scale);
                assertTrue(packed >= 0 && packed <= 0xFFFF,
                        "LOD-X/Z passt nicht in u16 bei Skala " + scale + ": " + coordinate);
                assertEquals(coordinate, packed / scale - LodMesher.XZ_POSITION_BIAS, 0F,
                        "LOD-X/Z wurde bei Skala " + scale + " verschoben");
            }
        }
    }

    @Test
    void generatedVertexShaderUsesTheSharedFlagAndFluidHeight() throws ReflectiveOperationException {
        Field sourceField = ChunkRenderer.class.getDeclaredField("VERTEX_SOURCE");
        sourceField.setAccessible(true);
        String source = (String) sourceField.get(null);

        assertFalse(source.contains("%d"));
        assertFalse(source.contains("%s"));
        assertTrue(source.contains("const uint FLAT_SOURCE_FLUID_TOP = "
                + ChunkMesher.FLAT_SOURCE_FLUID_TOP + "u;"));
        assertTrue(source.contains("const float SOURCE_FLUID_RENDER_HEIGHT = "
                + Float.toString(FluidGeometry.SOURCE_RENDER_HEIGHT) + ";"));
        assertTrue(source.contains("const float LOD_XZ_POSITION_BIAS = "
                + Float.toString(LodMesher.XZ_POSITION_BIAS) + ";"));
        assertTrue(source.contains("pos.xz -= scaleCode == 0u ? 1.0 : LOD_XZ_POSITION_BIAS;"));
        assertTrue(source.contains("pos.y = floor(pos.y) + SOURCE_FLUID_RENDER_HEIGHT;"));
    }

    private static float decodedWorldY(int worldBlockY, int origin, float scale) {
        float local = worldBlockY - origin + FluidGeometry.SOURCE_RENDER_HEIGHT;
        int packed = Math.round((local + 1F) * scale);
        return packed / scale - 1F + origin;
    }

    /** Entspricht der markierten Y-Rekonstruktion im ChunkRenderer-Vertex-Shader. */
    private static float shaderResolvedWorldY(int worldBlockY, int origin, float scale, int flags) {
        float decodedLocal = decodedWorldY(worldBlockY, origin, scale) - origin;
        if ((flags & ChunkMesher.FLAT_SOURCE_FLUID_TOP) != 0) {
            decodedLocal = (float) Math.floor(decodedLocal) + FluidGeometry.SOURCE_RENDER_HEIGHT;
        }
        return decodedLocal + origin;
    }

    private static int sectionOrigin(int worldY) {
        return Math.floorDiv(worldY, 32) * 32;
    }
}
