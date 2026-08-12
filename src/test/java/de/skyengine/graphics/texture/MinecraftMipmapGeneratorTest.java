package de.skyengine.graphics.texture;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMipmapGeneratorTest {

    @Test
    void transparentTexelsDoNotBleedRgbIntoCutoutMips() {
        int opaqueRed = 0xFF0000FF;
        int transparentBlue = 0x0000FF00;

        int result = MinecraftMipmapGenerator.alphaBlend(opaqueRed,
                transparentBlue, transparentBlue, transparentBlue, true);

        assertEquals(0x87000087, result);
    }

    @Test
    void generatedLevelUsesBaseImageTransparencyForMinecraftAlphaThreshold() {
        ByteBuffer source = ByteBuffer.allocateDirect(2 * 2 * 4);
        putRgba(source, 0, 255, 0, 0, 255);
        putRgba(source, 1, 0, 0, 255, 0);
        putRgba(source, 2, 0, 0, 255, 0);
        putRgba(source, 3, 0, 0, 255, 0);

        assertTrue(MinecraftMipmapGenerator.hasTransparentPixel(source, 2, 2));
        ByteBuffer mip = MinecraftMipmapGenerator.generateLevel(source, 2, 2, true);
        try {
            assertEquals(135, mip.get(0) & 0xFF);
            assertEquals(0, mip.get(1) & 0xFF);
            assertEquals(0, mip.get(2) & 0xFF);
            assertEquals(135, mip.get(3) & 0xFF);
        } finally {
            MemoryUtil.memFree(mip);
        }
    }

    private static void putRgba(ByteBuffer target, int pixel, int red, int green,
                                int blue, int alpha) {
        int offset = pixel * 4;
        target.put(offset, (byte) red);
        target.put(offset + 1, (byte) green);
        target.put(offset + 2, (byte) blue);
        target.put(offset + 3, (byte) alpha);
    }
}
