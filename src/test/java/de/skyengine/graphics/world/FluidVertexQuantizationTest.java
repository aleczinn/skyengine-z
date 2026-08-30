package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.FluidGeometry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluidVertexQuantizationTest {

    @Test
    void analyticFlatFluidHeightIsStableAtSectionPositionScale() {
        int[] worldBlockY = {0, 31, 63, 255, 511};

        for (int worldY : worldBlockY) {
            float expected = worldY + FluidGeometry.SOURCE_RENDER_HEIGHT;
            float resolved = shaderResolvedWorldY(worldY, sectionOrigin(worldY), ChunkMesher.POS_SCALE,
                    ChunkMesher.FLAT_SOURCE_FLUID_TOP);
            assertEquals(expected, resolved, 0.000001F);
        }
    }

    @Test
    void unmarkedFractionalGeometryKeepsItsPackedHeight() {
        float scale = ChunkMesher.POS_SCALE;
        int worldY = 63;
        float raw = decodedWorldY(worldY, 0, scale);

        assertEquals(raw, shaderResolvedWorldY(worldY, 0, scale, 0), 0F);
    }

    @Test
    void integerVerticalBoundaryCoordinatesRemainExact() {
        for (int coordinate : new int[]{0, 16, 32, 128, 512}) {
            int packed = Math.round((coordinate + 1F) * ChunkMesher.POS_SCALE);
            assertEquals(coordinate, packed / ChunkMesher.POS_SCALE - 1F, 0F);
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
        assertTrue(source.contains("pos.xz -= 1.0;"));
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
