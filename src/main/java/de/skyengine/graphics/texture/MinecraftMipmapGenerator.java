package de.skyengine.graphics.texture;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Minecraft 1.21.4's alpha-aware block-texture mipmap generator. */
final class MinecraftMipmapGenerator {

    private static final float[] GAMMA = new float[256];

    static {
        for (int value = 0; value < GAMMA.length; value++) {
            GAMMA[value] = (float) Math.pow(value / 255.0F, 2.2);
        }
    }

    private MinecraftMipmapGenerator() {
    }

    static boolean hasTransparentPixel(ByteBuffer pixels, int width, int height) {
        for (int index = 0; index < width * height; index++) {
            if ((pixels.get(index * 4 + 3) & 0xFF) == 0) return true;
        }
        return false;
    }

    static ByteBuffer generateLevel(ByteBuffer source, int width, int height,
                                    boolean hasTransparency) {
        int nextWidth = width >> 1;
        int nextHeight = height >> 1;
        ByteBuffer result = MemoryUtil.memAlloc(nextWidth * nextHeight * 4);

        for (int y = 0; y < nextHeight; y++) {
            for (int x = 0; x < nextWidth; x++) {
                int sourceX = x * 2;
                int sourceY = y * 2;
                int rgba = alphaBlend(
                        rgba(source, width, sourceX, sourceY),
                        rgba(source, width, sourceX + 1, sourceY),
                        rgba(source, width, sourceX, sourceY + 1),
                        rgba(source, width, sourceX + 1, sourceY + 1),
                        hasTransparency);
                int offset = (y * nextWidth + x) * 4;
                result.put(offset, (byte) (rgba >>> 24));
                result.put(offset + 1, (byte) (rgba >>> 16));
                result.put(offset + 2, (byte) (rgba >>> 8));
                result.put(offset + 3, (byte) rgba);
            }
        }
        return result;
    }

    /** RGBA-packed equivalent of Mojang's 1.21.4 alphaBlend method. */
    static int alphaBlend(int first, int second, int third, int fourth,
                          boolean hasTransparency) {
        if (!hasTransparency) {
            return rgba(
                    gammaAverage(first, second, third, fourth, 24),
                    gammaAverage(first, second, third, fourth, 16),
                    gammaAverage(first, second, third, fourth, 8),
                    gammaAverage(first, second, third, fourth, 0));
        }

        float red = 0F;
        float green = 0F;
        float blue = 0F;
        float alpha = 0F;
        int[] colors = {first, second, third, fourth};
        for (int color : colors) {
            if ((color & 0xFF) == 0) continue;
            red += gamma(color >>> 24);
            green += gamma(color >>> 16);
            blue += gamma(color >>> 8);
            alpha += gamma(color);
        }

        int outAlpha = fromLinear(alpha * 0.25F);
        if (outAlpha < 96) outAlpha = 0;
        return rgba(fromLinear(red * 0.25F), fromLinear(green * 0.25F),
                fromLinear(blue * 0.25F), outAlpha);
    }

    private static int rgba(ByteBuffer pixels, int width, int x, int y) {
        int offset = (y * width + x) * 4;
        return rgba(pixels.get(offset) & 0xFF,
                pixels.get(offset + 1) & 0xFF,
                pixels.get(offset + 2) & 0xFF,
                pixels.get(offset + 3) & 0xFF);
    }

    private static int rgba(int red, int green, int blue, int alpha) {
        return red << 24 | green << 16 | blue << 8 | alpha;
    }

    private static int gammaAverage(int first, int second, int third, int fourth,
                                    int shift) {
        float average = (gamma(first >>> shift) + gamma(second >>> shift)
                + gamma(third >>> shift) + gamma(fourth >>> shift)) * 0.25F;
        return fromLinear(average);
    }

    private static float gamma(int value) {
        return GAMMA[value & 0xFF];
    }

    private static int fromLinear(float value) {
        return (int) (Math.pow(value, 1.0 / 2.2) * 255.0);
    }
}
