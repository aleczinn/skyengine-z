package de.skyengine.game.world.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackedTerrainQuadTest {

    @Test
    void geometryUsesEveryBitWithoutLosingBoundaryValues() {
        int low = PackedTerrainQuad.geometry0(0, 0, 0, 0, false, 1, 1, 0, false);
        assertEquals(0, PackedTerrainQuad.x(low));
        assertEquals(1, PackedTerrainQuad.width(low));
        assertFalse(PackedTerrainQuad.positive(low));
        assertFalse(PackedTerrainQuad.diagonalFlip(low));

        int high = PackedTerrainQuad.geometry0(31, 31, 31, 2, true, 32, 32, 7, true);
        assertEquals(31, PackedTerrainQuad.x(high));
        assertEquals(31, PackedTerrainQuad.y(high));
        assertEquals(31, PackedTerrainQuad.z(high));
        assertEquals(2, PackedTerrainQuad.axis(high));
        assertTrue(PackedTerrainQuad.positive(high));
        assertEquals(32, PackedTerrainQuad.width(high));
        assertEquals(32, PackedTerrainQuad.height(high));
        assertEquals(7, PackedTerrainQuad.uvTransform(high));
        assertTrue(PackedTerrainQuad.diagonalFlip(high));

        int material = PackedTerrainQuad.geometry1(0xFEDC, 0xBA, 0x98);
        assertEquals(0xFEDC, PackedTerrainQuad.materialId(material));
        assertEquals(0xBA, PackedTerrainQuad.tintIndex(material));
        assertEquals(0x98, PackedTerrainQuad.flags(material));
    }

    @Test
    void sampleSumsRoundTripEveryRepresentableSmoothLightValue() {
        for (int sum = 0; sum <= 60; sum++) {
            int encodedByte = PackedTerrainQuad.sampleSumToByteLight(sum);
            assertEquals(sum, PackedTerrainQuad.byteLightToSampleSum(encodedByte));
        }
    }

    @Test
    void genericFallbackReplicatesMonochromeBlockLightIntoRgbAndKeepsFlags() {
        int internal = VertexLight.average(58, 37, 4) | 0xA5 << VertexLight.FIRST_FLAG_BIT;
        int packed = VertexLight.packGenericRgb(internal);
        assertEquals(VertexLight.sky(internal), VertexLight.genericSky(packed));
        assertEquals(VertexLight.block(internal), VertexLight.genericRed(packed));
        assertEquals(VertexLight.block(internal), VertexLight.genericGreen(packed));
        assertEquals(VertexLight.block(internal), VertexLight.genericBlue(packed));
        assertEquals(0xA5, packed >>> VertexLight.FIRST_FLAG_BIT);
    }

    @Test
    void cornerPayloadPreservesRgbAoAndTheFullFixedTint() {
        int tint = 0xA1B2C3;
        int[] corners = new int[4];
        for (int corner = 0; corner < 4; corner++) {
            corners[corner] = PackedTerrainQuad.cornerShading(
                    60 - corner, 10 + corner, 20 + corner, 30 + corner,
                    corner, tint, corner);
            assertEquals(60 - corner, PackedTerrainQuad.skySum(corners[corner]));
            assertEquals(10 + corner, PackedTerrainQuad.redSum(corners[corner]));
            assertEquals(20 + corner, PackedTerrainQuad.greenSum(corners[corner]));
            assertEquals(30 + corner, PackedTerrainQuad.blueSum(corners[corner]));
            assertEquals(corner, PackedTerrainQuad.ao(corners[corner]));
        }
        assertEquals(tint, PackedTerrainQuad.fixedTint(
                corners[0], corners[1], corners[2], corners[3]));
    }

    @Test
    void invalidValuesCannotSilentlyBleedIntoAdjacentFields() {
        assertThrows(IllegalArgumentException.class,
                () -> PackedTerrainQuad.geometry0(32, 0, 0, 0, false, 1, 1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> PackedTerrainQuad.geometry0(0, 0, 0, 0, false, 33, 1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> PackedTerrainQuad.uniformShading(61, 0, 0, 0, 0));
    }
}
