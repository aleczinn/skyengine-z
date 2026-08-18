package de.skyengine.graphics.texture;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Set;

/** GPU-Texture-Array fuer Blockfarben und optionale Material-Sidecars. */
public class TextureArray {
    public enum Fallback { MISSING, FLAT_NORMAL, DEFAULT_MATERIAL }

    private final Logger logger = LogManager.getLogger(TextureArray.class.getName());
    private int id;
    private int size;

    public TextureArray(int size, String[] paths) { this(size, paths, Set.of(), Fallback.MISSING); }
    public TextureArray(int size, String[] paths, Set<Integer> skipLayers) {
        this(size, paths, skipLayers, Fallback.MISSING);
    }

    public TextureArray(int size, String[] paths, Set<Integer> skipLayers, Fallback fallback) {
        this.size = size;
        int layerCount = Math.max(paths.length, 1);
        this.id = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
        int mipLevels = (int) (Math.log(size) / Math.log(2)) + 1;
        GL42.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, mipLevels, GL11.GL_RGBA8, size, size, layerCount);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
            for (int layer = 0; layer < paths.length; layer++) {
                if (skipLayers.contains(layer)) continue;
                w.put(0, 0); h.put(0, 0);
                ByteBuffer source = StbImageLoader.load(new FileHandle(paths[layer], FileType.RESOURCE), w, h, c, 4);
                ByteBuffer upload;
                boolean stbOwned = false;
                if (source == null) {
                    upload = fallbackTexture(size, fallback);
                    if (fallback == Fallback.MISSING) this.logger.warning("Textur fehlt: " + paths[layer]);
                } else {
                    stbOwned = true;
                    int sourceW = w.get(0), sourceH = h.get(0);
                    int frame = Math.min(sourceW, sourceH);
                    if (frame <= 0 || sourceW % frame != 0 || sourceH % frame != 0) {
                        this.logger.warning("Textur hat kein quadratisches Frame-Raster: " + paths[layer]);
                        STBImage.stbi_image_free(source);
                        stbOwned = false;
                        upload = fallbackTexture(size, fallback);
                    } else if (sourceW != size || sourceH != size) {
                        upload = resampleNearest(source, sourceW, frame, size);
                        STBImage.stbi_image_free(source);
                        stbOwned = false;
                    } else {
                        upload = source;
                    }
                    if (fallback == Fallback.MISSING) bleedAlpha(upload, size, size);
                    else forcePresentAlpha(upload, size);
                }
                GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer,
                        size, size, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);
                if (stbOwned) STBImage.stbi_image_free(upload); else MemoryUtil.memFree(upload);
            }
        }

        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, Math.min(4, mipLevels - 1));
        float maxAniso = GL11.glGetFloat(GL46.GL_MAX_TEXTURE_MAX_ANISOTROPY);
        GL11.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL46.GL_TEXTURE_MAX_ANISOTROPY,
                Math.min(GameSettings.get().anisotropicFiltering, maxAniso));
        de.skyengine.graphics.GlDebug.labelTexture(this.id, "Block-TextureArray (" + paths.length + " Layer)");
        this.logger.info("TextureArray erstellt: " + paths.length + " Layer a " + size + "x" + size);
    }

    /** Hoechste referenzierte Pack-Aufloesung, Zweierpotenz 16..256. */
    public static int detectSize(String[] paths) {
        int detected = 16;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
            for (String path : paths) {
                w.put(0, 0); h.put(0, 0);
                ByteBuffer pixels = StbImageLoader.load(new FileHandle(path, FileType.RESOURCE), w, h, c, 4);
                if (pixels == null) continue;
                STBImage.stbi_image_free(pixels);
                int frame = Math.min(w.get(0), h.get(0));
                if (frame >= 16 && frame <= 256 && (frame & (frame - 1)) == 0
                        && w.get(0) % frame == 0 && h.get(0) % frame == 0) detected = Math.max(detected, frame);
            }
        }
        return detected;
    }

    private static ByteBuffer resampleNearest(ByteBuffer source, int sourceWidth, int sourceSize, int targetSize) {
        ByteBuffer out = MemoryUtil.memAlloc(targetSize * targetSize * 4);
        for (int y = 0; y < targetSize; y++) {
            int sy = y * sourceSize / targetSize;
            for (int x = 0; x < targetSize; x++) {
                int sx = x * sourceSize / targetSize;
                int src = (sy * sourceWidth + sx) * 4;
                out.put(source.get(src)).put(source.get(src + 1)).put(source.get(src + 2)).put(source.get(src + 3));
            }
        }
        return out.flip();
    }

    private static void forcePresentAlpha(ByteBuffer pixels, int size) {
        for (int i = 0; i < size * size; i++) pixels.put(i * 4 + 3, (byte) 255);
    }

    private static void bleedAlpha(ByteBuffer pixels, int width, int height) {
        boolean[] filled = new boolean[width * height];
        for (int i = 0; i < filled.length; i++) filled[i] = (pixels.get(i * 4 + 3) & 0xFF) != 0;
        int[] dx = {0, 0, -1, 1}, dy = {-1, 1, 0, 0};
        boolean changed = true;
        for (int pass = 0; pass < Math.max(width, height) && changed; pass++) {
            changed = false;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (filled[index]) continue;
                for (int n = 0; n < 4; n++) {
                    int nx = x + dx[n], ny = y + dy[n];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int neighbor = ny * width + nx;
                    if (!filled[neighbor]) continue;
                    pixels.put(index * 4, pixels.get(neighbor * 4));
                    pixels.put(index * 4 + 1, pixels.get(neighbor * 4 + 1));
                    pixels.put(index * 4 + 2, pixels.get(neighbor * 4 + 2));
                    filled[index] = true;
                    changed = true;
                    break;
                }
            }
        }
    }

    private static ByteBuffer fallbackTexture(int size, Fallback fallback) {
        ByteBuffer buffer = MemoryUtil.memAlloc(size * size * 4);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (fallback == Fallback.FLAT_NORMAL) {
                buffer.put((byte) 128).put((byte) 128).put((byte) 255).put((byte) 0);
            } else if (fallback == Fallback.DEFAULT_MATERIAL) {
                buffer.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 0);
            } else {
                boolean magenta = ((x / Math.max(1, size / 2)) + (y / Math.max(1, size / 2))) % 2 == 0;
                buffer.put((byte) (magenta ? 255 : 0)).put((byte) 0)
                        .put((byte) (magenta ? 255 : 0)).put((byte) 255);
            }
        }
        return buffer.flip();
    }

    public void regenerateMipmaps() {
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
    }
    public void setLodBias(float bias) {
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
        GL11.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL14.GL_TEXTURE_LOD_BIAS, bias);
    }
    public void updateLayer(int layer, ByteBuffer rgba) {
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
        GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer,
                this.size, this.size, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
    }
    public void bind(int unit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
    }
    public void replaceWith(TextureArray replacement) {
        if (this.id != -1) GL11.glDeleteTextures(this.id);
        this.id = replacement.id;
        this.size = replacement.size;
        replacement.id = -1;
    }
    public int size() { return this.size; }
    public void dispose() {
        if (this.id != -1) GL11.glDeleteTextures(this.id);
        this.id = -1;
    }
}
