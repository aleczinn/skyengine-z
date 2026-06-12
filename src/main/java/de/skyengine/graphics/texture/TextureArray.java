package de.skyengine.graphics.texture;

import de.skyengine.core.file.Files;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class TextureArray {

	private final Logger logger = LogManager.getLogger(TextureArray.class.getName());
	private final int id;

	/**
	 * @param size  Kantenlänge aller Texturen (z.B. 16) - ALLE Texturen müssen size x size sein!
	 * @param paths Pfade relativ zum resources-Ordner, Index = Layer im Array
	 */
	public TextureArray(int size, String[] paths) {
		int layerCount = Math.max(paths.length, 1);

		this.id = GL11.glGenTextures();
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);

		int mipLevels = (int) (Math.log(size) / Math.log(2)) + 1;
		GL42.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, mipLevels, GL11.GL_RGBA8, size, size, layerCount);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);

			for (int layer = 0; layer < paths.length; layer++) {
				String fullPath = Files.RESOURCES_PATH + paths[layer];
				ByteBuffer pixels = STBImage.stbi_load(fullPath, w, h, c, 4);

				if (pixels == null || w.get(0) != size || h.get(0) != size) {
					if (pixels != null) STBImage.stbi_image_free(pixels);
					this.logger.warning("Textur fehlt oder falsche Größe (" + size + "x" + size + " erwartet): " + paths[layer]);
					pixels = missingTexture(size);
					GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
					MemoryUtil.memFree(pixels);
				} else {
					GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
					STBImage.stbi_image_free(pixels);
				}
			}
		}

		GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);

		/* The Minecraft look: crisp pixels up close, smooth mip transitions in the distance */
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR);
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 4);

		float maxAniso = GL11.glGetFloat(GL46.GL_MAX_TEXTURE_MAX_ANISOTROPY);
		GL11.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL46.GL_TEXTURE_MAX_ANISOTROPY, Math.min(8.0F, maxAniso));

		this.logger.info("TextureArray erstellt: " + paths.length + " Layer à " + size + "x" + size);
	}

	/** Magenta/Schwarz-Schachbrett als Platzhalter für fehlende Texturen. */
	private static ByteBuffer missingTexture(int size) {
		ByteBuffer buffer = MemoryUtil.memAlloc(size * size * 4);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean magenta = ((x / (size / 2)) + (y / (size / 2))) % 2 == 0;
				buffer.put((byte) (magenta ? 255 : 0)); // r
				buffer.put((byte) 0);                   // g
				buffer.put((byte) (magenta ? 255 : 0)); // b
				buffer.put((byte) 255);                 // a
			}
		}
		buffer.flip();
		return buffer;
	}

	public void bind(int unit) {
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
	}

	public void dispose() {
		GL11.glDeleteTextures(this.id);
	}
}