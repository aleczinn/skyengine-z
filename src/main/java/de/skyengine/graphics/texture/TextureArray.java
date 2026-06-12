package de.skyengine.graphics.texture;

import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class TextureArray {

	private final int id;

	public TextureArray(int size, String[] paths) {
		this.id = GL11.glGenTextures();
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);

		/* 16x16 -> 5 mip levels (16,8,4,2,1). Limit to 4 used levels to keep distant blocks crisp */
		int mipLevels = (int) (Math.log(size) / Math.log(2)) + 1;
		GL42.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, mipLevels, GL11.GL_RGBA8, size, size, paths.length);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
			for (int layer = 0; layer < paths.length; layer++) {
				ByteBuffer pixels = STBImage.stbi_load(paths[layer], w, h, c, 4);
				if (pixels == null) throw new RuntimeException("Texture not found: " + paths[layer]);
				GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
				STBImage.stbi_image_free(pixels);
			}
		}

		GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);

		/* The Minecraft look: crisp pixels up close, smooth mip transitions in the distance */
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR);
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 4);

		/* Anisotropic filtering is core in GL 4.6 - big win for flat ground at shallow angles */
		float maxAniso = GL11.glGetFloat(GL46.GL_MAX_TEXTURE_MAX_ANISOTROPY);
		GL11.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL46.GL_TEXTURE_MAX_ANISOTROPY, Math.min(8.0F, maxAniso));
	}

	public void bind(int unit) {
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
	}

	public void dispose() {
		GL11.glDeleteTextures(this.id);
	}
}