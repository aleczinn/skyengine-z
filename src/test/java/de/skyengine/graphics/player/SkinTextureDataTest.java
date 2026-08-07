package de.skyengine.graphics.player;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SkinTextureDataTest {

    @Test
    void bundledDefaultSkinLoadsAsModernAtlas() {
        SkinTextureData data = SkinTextureData.load(
                new FileHandle("game/textures/entity/player/steve.png", FileType.RESOURCE));

        assertFalse(data.isLegacy());
        assertFalse(data.isSlim());
        assertEquals(64 * 64 * 4, data.rgba().length);
    }

    @Test
    void modernSkinRemainsUnchangedAndKeepsSlimDetection() {
        byte[] pixels = new byte[64 * 64 * 4];
        Arrays.fill(pixels, (byte) 0x7F);
        setAlpha(pixels, 54, 20, 0);
        setAlpha(pixels, 55, 20, 0);
        setAlpha(pixels, 55, 31, 0);

        SkinTextureData data = SkinTextureData.normalize(pixels, 64, 64);

        assertArrayEquals(pixels, data.rgba());
        assertFalse(data.isLegacy());
        assertTrue(data.isSlim());
    }

    @Test
    void legacySkinUsesClassicModelAndLeavesOverlayLayersTransparent() {
        byte[] pixels = patternedLegacySkin();

        SkinTextureData data = SkinTextureData.normalize(pixels, 64, 32);

        assertTrue(data.isLegacy());
        assertFalse(data.isSlim());
        assertArrayEquals(Arrays.copyOf(pixels, pixels.length),
                Arrays.copyOf(data.rgba(), pixels.length));
        assertPixel(data.rgba(), 0, 32, 0, 0, 0, 0);
        assertPixel(data.rgba(), 15, 47, 0, 0, 0, 0);
        assertPixel(data.rgba(), 48, 48, 0, 0, 0, 0);
        assertPixel(data.rgba(), 63, 63, 0, 0, 0, 0);
    }

    @Test
    void legacyRightLegAndArmFacesAreMirroredIntoModernLeftUvs() {
        byte[] source = patternedLegacySkin();
        byte[] converted = SkinTextureData.normalize(source, 64, 32).rgba();

        assertMirroredRect(source, converted, 4, 16, 20, 48, 4, 4);
        assertMirroredRect(source, converted, 8, 16, 24, 48, 4, 4);
        assertMirroredRect(source, converted, 0, 20, 24, 52, 4, 12);
        assertMirroredRect(source, converted, 4, 20, 20, 52, 4, 12);
        assertMirroredRect(source, converted, 8, 20, 16, 52, 4, 12);
        assertMirroredRect(source, converted, 12, 20, 28, 52, 4, 12);
        assertMirroredRect(source, converted, 44, 16, 36, 48, 4, 4);
        assertMirroredRect(source, converted, 48, 16, 40, 48, 4, 4);
        assertMirroredRect(source, converted, 40, 20, 40, 52, 4, 12);
        assertMirroredRect(source, converted, 44, 20, 36, 52, 4, 12);
        assertMirroredRect(source, converted, 48, 20, 32, 52, 4, 12);
        assertMirroredRect(source, converted, 52, 20, 44, 52, 4, 12);
    }

    @Test
    void rejectsNonVanillaDimensionsAndMalformedBuffers() {
        assertThrows(IllegalArgumentException.class,
                () -> SkinTextureData.normalize(new byte[128 * 128 * 4], 128, 128));
        assertThrows(IllegalArgumentException.class,
                () -> SkinTextureData.normalize(new byte[1], 64, 32));
    }

    private static byte[] patternedLegacySkin() {
        byte[] pixels = new byte[64 * 32 * 4];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                int index = (y * 64 + x) * 4;
                pixels[index] = (byte) x;
                pixels[index + 1] = (byte) y;
                pixels[index + 2] = (byte) (x ^ y);
                pixels[index + 3] = (byte) 255;
            }
        }
        return pixels;
    }

    private static void assertMirroredRect(byte[] source, byte[] target,
                                           int sourceX, int sourceY, int targetX, int targetY,
                                           int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertPixelEquals(source, sourceX + width - 1 - x, sourceY + y,
                        target, targetX + x, targetY + y);
            }
        }
    }

    private static void assertPixelEquals(byte[] expected, int expectedX, int expectedY,
                                          byte[] actual, int actualX, int actualY) {
        int expectedIndex = (expectedY * 64 + expectedX) * 4;
        int actualIndex = (actualY * 64 + actualX) * 4;
        assertArrayEquals(Arrays.copyOfRange(expected, expectedIndex, expectedIndex + 4),
                Arrays.copyOfRange(actual, actualIndex, actualIndex + 4));
    }

    private static void assertPixel(byte[] pixels, int x, int y, int r, int g, int b, int a) {
        int index = (y * 64 + x) * 4;
        assertArrayEquals(new byte[]{(byte) r, (byte) g, (byte) b, (byte) a},
                Arrays.copyOfRange(pixels, index, index + 4));
    }

    private static void setAlpha(byte[] pixels, int x, int y, int alpha) {
        pixels[(y * 64 + x) * 4 + 3] = (byte) alpha;
    }
}
