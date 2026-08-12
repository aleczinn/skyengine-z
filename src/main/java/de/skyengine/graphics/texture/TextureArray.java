package de.skyengine.graphics.texture;

import de.skyengine.core.file.Files;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL46;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Set;

public class TextureArray {

    private final Logger logger = LogManager.getLogger(TextureArray.class.getName());
    private final int id;
    private final int size;
    private final int mipLevels;

    public TextureArray(int size, String[] paths) {
        this(size, paths, Set.of());
    }

    /**
     * @param size edge length of every static texture
     * @param paths resource paths; the index is the array layer
     * @param skipLayers animated layers filled by SpriteAnimations afterwards
     */
    public TextureArray(int size, String[] paths, Set<Integer> skipLayers) {
        this.size = size;
        this.mipLevels = (int) (Math.log(size) / Math.log(2)) + 1;
        int layerCount = Math.max(paths.length, 1);

        this.id = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
        GL42.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, this.mipLevels, GL11.GL_RGBA8,
                size, size, layerCount);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            for (int layer = 0; layer < paths.length; layer++) {
                if (skipLayers.contains(layer)) continue;
                String fullPath = Files.RESOURCES_PATH + paths[layer];
                ByteBuffer pixels = STBImage.stbi_load(fullPath, width, height, channels, 4);

                if (pixels == null || width.get(0) != size || height.get(0) != size) {
                    if (pixels != null) STBImage.stbi_image_free(pixels);
                    this.logger.warning("Textur fehlt oder falsche Groesse (" + size + "x"
                            + size + " erwartet): " + paths[layer]);
                    pixels = missingTexture(size);
                    uploadMipChain(layer, pixels);
                    MemoryUtil.memFree(pixels);
                } else {
                    uploadMipChain(layer, pixels);
                    STBImage.stbi_image_free(pixels);
                }
            }
        }

        // Matches Minecraft's non-blurred mipmapped block atlas.
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL,
                Math.min(4, this.mipLevels - 1));

        float maxAnisotropy = GL11.glGetFloat(GL46.GL_MAX_TEXTURE_MAX_ANISOTROPY);
        float anisotropy = de.skyengine.core.settings.GameSettings.get().anisotropicFiltering;
        GL11.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL46.GL_TEXTURE_MAX_ANISOTROPY,
                Math.min(anisotropy, maxAnisotropy));

        de.skyengine.graphics.GlDebug.labelTexture(this.id,
                "Block-TextureArray (" + paths.length + " Layer)");
        this.logger.info("TextureArray erstellt: " + paths.length + " Layer a "
                + size + "x" + size);
    }

    private void uploadMipChain(int layer, ByteBuffer baseLevel) {
        boolean hasTransparency = MinecraftMipmapGenerator.hasTransparentPixel(
                baseLevel, this.size, this.size);
        ByteBuffer levelPixels = baseLevel;
        int width = this.size;
        int height = this.size;

        for (int level = 0; level < this.mipLevels; level++) {
            GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, 0, 0, layer,
                    width, height, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, levelPixels);
            if (level + 1 >= this.mipLevels) break;

            ByteBuffer next = MinecraftMipmapGenerator.generateLevel(
                    levelPixels, width, height, hasTransparency);
            if (levelPixels != baseLevel) MemoryUtil.memFree(levelPixels);
            levelPixels = next;
            width >>= 1;
            height >>= 1;
        }
        if (levelPixels != baseLevel) MemoryUtil.memFree(levelPixels);
    }

    private static ByteBuffer missingTexture(int size) {
        ByteBuffer buffer = MemoryUtil.memAlloc(size * size * 4);
        int tileSize = Math.max(size / 2, 1);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean magenta = ((x / tileSize) + (y / tileSize)) % 2 == 0;
                buffer.put((byte) (magenta ? 255 : 0));
                buffer.put((byte) 0);
                buffer.put((byte) (magenta ? 255 : 0));
                buffer.put((byte) 255);
            }
        }
        buffer.flip();
        return buffer;
    }

    /** Negative values select a finer mip level while TAA is active. */
    public void setLodBias(float bias) {
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
        GL11.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL14.GL_TEXTURE_LOD_BIAS, bias);
    }

    /** Replaces an animated layer including its Minecraft-identical mip chain. */
    public void updateLayer(int layer, ByteBuffer rgba) {
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
        uploadMipChain(layer, rgba);
    }

    public void bind(int unit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
    }

    public void dispose() {
        GL11.glDeleteTextures(this.id);
    }
}
