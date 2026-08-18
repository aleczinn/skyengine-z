package de.skyengine.graphics.player;

import de.skyengine.core.file.FileHandle;
import de.skyengine.graphics.texture.StbImageLoader;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** CPU-seitiger, auf Vanillas 64x64-Atlas normalisierter Spieler-Skin. */
final class SkinTextureData {

    static final int WIDTH = 64;
    static final int HEIGHT = 64;
    private static final int CHANNELS = 4;

    private final byte[] rgba;
    private final boolean legacy;

    private SkinTextureData(byte[] rgba, boolean legacy) {
        this.rgba = rgba;
        this.legacy = legacy;
    }

    static SkinTextureData load(FileHandle file) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = StbImageLoader.load(file, width, height, channels, CHANNELS);
            if (pixels == null) {
                throw new IllegalArgumentException("Skin konnte nicht dekodiert werden: " + file.path());
            }
            try {
                byte[] rgba = new byte[width.get(0) * height.get(0) * CHANNELS];
                pixels.get(0, rgba);
                return normalize(rgba, width.get(0), height.get(0));
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        }
    }

    static SkinTextureData normalize(byte[] rgba, int width, int height) {
        int expected = Math.multiplyExact(Math.multiplyExact(width, height), CHANNELS);
        if (rgba.length != expected) {
            throw new IllegalArgumentException("RGBA-Daten haben " + rgba.length + " statt " + expected + " Bytes");
        }
        if (width == WIDTH && height == HEIGHT) {
            return new SkinTextureData(rgba.clone(), false);
        }
        if (width != WIDTH || height != 32) {
            throw new IllegalArgumentException("Skin ist " + width + "x" + height
                    + " - unterstuetzt werden 64x64 und Legacy 64x32");
        }

        byte[] modern = new byte[WIDTH * HEIGHT * CHANNELS];
        System.arraycopy(rgba, 0, modern, 0, rgba.length);

        // Vanilla PlayerSkinTexture/SkinTextureDownloader: Die sechs Flaechen des rechten
        // Beins werden einzeln und horizontal gespiegelt in den linken Bein-Atlas kopiert.
        copyMirrored(rgba, modern, 4, 16, 20, 48, 4, 4);
        copyMirrored(rgba, modern, 8, 16, 24, 48, 4, 4);
        copyMirrored(rgba, modern, 0, 20, 24, 52, 4, 12);
        copyMirrored(rgba, modern, 4, 20, 20, 52, 4, 12);
        copyMirrored(rgba, modern, 8, 20, 16, 52, 4, 12);
        copyMirrored(rgba, modern, 12, 20, 28, 52, 4, 12);

        // Dasselbe fuer den rechten Arm. Legacy-Skins besitzen keine separaten linken
        // Gliedmassen und keine Jacken-/Aermel-/Hosen-Layer; deren Bereiche bleiben transparent.
        copyMirrored(rgba, modern, 44, 16, 36, 48, 4, 4);
        copyMirrored(rgba, modern, 48, 16, 40, 48, 4, 4);
        copyMirrored(rgba, modern, 40, 20, 40, 52, 4, 12);
        copyMirrored(rgba, modern, 44, 20, 36, 52, 4, 12);
        copyMirrored(rgba, modern, 48, 20, 32, 52, 4, 12);
        copyMirrored(rgba, modern, 52, 20, 44, 52, 4, 12);
        return new SkinTextureData(modern, true);
    }

    private static void copyMirrored(byte[] source, byte[] target,
                                     int sourceX, int sourceY, int targetX, int targetY,
                                     int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sourceIndex = ((sourceY + y) * WIDTH + sourceX + (width - 1 - x)) * CHANNELS;
                int targetIndex = ((targetY + y) * WIDTH + targetX + x) * CHANNELS;
                System.arraycopy(source, sourceIndex, target, targetIndex, CHANNELS);
            }
        }
    }

    byte[] rgba() {
        return this.rgba;
    }

    boolean isLegacy() {
        return this.legacy;
    }

    boolean isSlim() {
        if (this.legacy) return false;
        return alpha(54, 20) == 0 && alpha(55, 20) == 0 && alpha(55, 31) == 0;
    }

    private int alpha(int x, int y) {
        return this.rgba[(y * WIDTH + x) * CHANNELS + 3] & 0xFF;
    }
}
