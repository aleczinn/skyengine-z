package de.skyengine.game.world.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PackedQuadTest {

    @Test
    void baseQuadRoundTripsEveryFieldInEightBytes() {
        long packed = PackedQuad.pack(31, 17, 5, PackedQuad.AXIS_Z, true,
                32, 19, 7, true, 65535, 203, 0xA5);

        assertEquals(8, PackedQuad.BASE_BYTES);
        assertEquals(31, PackedQuad.x(packed));
        assertEquals(17, PackedQuad.y(packed));
        assertEquals(5, PackedQuad.z(packed));
        assertEquals(PackedQuad.AXIS_Z, PackedQuad.axis(packed));
        assertTrue(PackedQuad.positiveSide(packed));
        assertEquals(32, PackedQuad.width(packed));
        assertEquals(19, PackedQuad.height(packed));
        assertEquals(7, PackedQuad.uvTransform(packed));
        assertTrue(PackedQuad.flippedDiagonal(packed));
        assertEquals(65535, PackedQuad.material(packed));
        assertEquals(203, PackedQuad.tintIndex(packed));
        assertEquals(0xA5, PackedQuad.flags(packed));
    }

    @Test
    void shadingPreservesRgbLightSumsIncludingWordBoundary() {
        int[][] light = new int[4][4];
        int value = 0;
        for (int corner = 0; corner < 4; corner++) for (int channel = 0; channel < 4; channel++) {
            light[corner][channel] = value++ * 4;
        }
        int[] ao = {0, 1, 2, 3};
        PackedQuad.Shading shading = PackedQuad.Shading.pack(light, ao, 0x12ABEF);

        assertEquals(24, PackedQuad.SHADED_BYTES);
        for (int corner = 0; corner < 4; corner++) for (int channel = 0; channel < 4; channel++) {
            assertEquals(light[corner][channel], shading.lightSum(corner, channel));
        }
        for (int corner = 0; corner < 4; corner++) assertEquals(ao[corner], shading.ao(corner));
        assertEquals(0x12ABEF, shading.tintRgb());
    }

    @Test
    void rejectsGeometryThatCannotBeRepresented() {
        assertThrows(IllegalArgumentException.class, () -> PackedQuad.pack(32, 0, 0, 0,
                false, 1, 1, 0, false, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PackedQuad.pack(0, 0, 0, 0,
                false, 0, 1, 0, false, 0, 0, 0));
    }
}
