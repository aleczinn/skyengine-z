package de.skyengine.graphics.texture;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.io.IDisposable;
import de.skyengine.utils.math.MathUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

public class Texture implements IDisposable {

	public static final float DEFAULT_U = 0;
	public static final float DEFAULT_V = 1;
	public static final float DEFAULT_U2 = 1;
	public static final float DEFAULT_V2 = 0;

	private int textureID = -1;
	private int width;
	private int height;
	
	private TextureFilter minFilter = TextureFilter.NEAREST;
	private TextureFilter magFilter = TextureFilter.NEAREST;
	
	private TextureWrap wrapU = TextureWrap.CLAMP_TO_EDGE;
	private TextureWrap wrapV = TextureWrap.CLAMP_TO_EDGE;
	
	private static float maxAnisotropicFilterLevel = 0.0F;
	private float anisotropicFilterLevel = 1.0F;

	public Texture(FileHandle file) {
		this(file, true);
	}

	public Texture(FileHandle file, boolean useMipMaps) {
		this.textureID = GL11.glGenTextures();
		this.bind();

		if(!file.exists()) throw new RuntimeException("Path could not be found!");

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
			ByteBuffer pixels = StbImageLoader.load(file, w, h, c, 4);
			if (pixels == null) throw new RuntimeException("Texture not found: " + file.path());

			this.width = w.get();
			this.height = h.get();

			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.width, this.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
			STBImage.stbi_image_free(pixels);
		}

		if(useMipMaps) {
			GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
		}

		this.unsafeSetFilter(this.minFilter, this.magFilter, true);
		this.unsafeSetWrap(this.wrapU, this.wrapV, true);
		this.unsafeSetAnisotropicFilterLevel(this.anisotropicFilterLevel, true);
		
		this.unbind();
	}

	/** Erstellt eine Textur aus einem kodierten PNG/JPEG im Speicher (z.B. pack.png aus einem ZIP). */
	public Texture(byte[] encoded, boolean useMipMaps) {
		if (encoded == null || encoded.length == 0) throw new IllegalArgumentException("Empty texture data");
		ByteBuffer input = MemoryUtil.memAlloc(encoded.length);
		ByteBuffer pixels = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			input.put(encoded).flip();
			IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
			pixels = STBImage.stbi_load_from_memory(input, w, h, c, 4);
			if (pixels == null) throw new IllegalArgumentException("Invalid encoded texture");
			this.textureID = GL11.glGenTextures();
			this.bind();
			this.width = w.get(0);
			this.height = h.get(0);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.width, this.height, 0,
					GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
			if (useMipMaps) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
			this.unsafeSetFilter(this.minFilter, this.magFilter, true);
			this.unsafeSetWrap(this.wrapU, this.wrapV, true);
			this.unsafeSetAnisotropicFilterLevel(this.anisotropicFilterLevel, true);
			this.unbind();
		} finally {
			if (pixels != null) STBImage.stbi_image_free(pixels);
			MemoryUtil.memFree(input);
		}
	}

	/**
	 * Erstellt eine RGBA8-Textur aus bereits dekodierten Pixeln. Der Puffer wird nur während
	 * dieses Aufrufs gelesen und bleibt im Besitz des Aufrufers.
	 */
	public Texture(int width, int height, ByteBuffer pixels, boolean useMipMaps) {
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid texture size: " + width + "x" + height);
		if (pixels.remaining() < width * height * 4) {
			throw new IllegalArgumentException("RGBA buffer too small for " + width + "x" + height);
		}

		this.textureID = GL11.glGenTextures();
		this.bind();
		this.width = width;
		this.height = height;
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

		if (useMipMaps) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
		this.unsafeSetFilter(this.minFilter, this.magFilter, true);
		this.unsafeSetWrap(this.wrapU, this.wrapV, true);
		this.unsafeSetAnisotropicFilterLevel(this.anisotropicFilterLevel, true);
		this.unbind();
	}
	
	public void bind() {
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureID);
	}
	
	public void bind(int unit) {
		GL14.glActiveTexture(GL14.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureID);
	}
	
	public void unbind() {
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
	}
	
	public void setFilter(TextureFilter minFilter, TextureFilter magFilter) {
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		
		this.bind();
		
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter.getGlEnum());
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter.getGlEnum());
	}
	
	/**
	 * Sets the {@link TextureFilter} for this texture for minification and magnification. Assumes the texture is bound and active!
	 * @param minFilter the minification filter
	 * @param magFilter the magnification filter
	 * @param force True to always set the values, even if they are the same as the current values.
	 */
	public void unsafeSetFilter(TextureFilter minFilter, TextureFilter magFilter, boolean force) {
		if(minFilter != null && (force || this.minFilter != minFilter)) {
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter.getGlEnum());
			this.minFilter = minFilter;
		}
		if(magFilter != null && (force || this.magFilter != magFilter)) {
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter.getGlEnum());
			this.magFilter = magFilter;
		}
	}
	
	public void setWrap(TextureWrap wrapU, TextureWrap wrapV) {
		this.wrapU = wrapU;
		this.wrapV = wrapV;
		
		this.bind();
		
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_WRAP_S, wrapU.getGlEnum());
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_WRAP_T, wrapV.getGlEnum());
	}
	
	/**
	 * Sets the {@link TextureWrap} for this texture on the u and v axis. Assumes the texture is bound and active!
	 * @param wrapU the u wrap
	 * @param wrapV the v wrap
	 * @param force True to always set the values, even if they are the same as the current values.
	 */
	public void unsafeSetWrap(TextureWrap wrapU, TextureWrap wrapV, boolean force) {
		if(wrapU != null && (force || this.wrapU != wrapU)) {
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_WRAP_S, wrapU.getGlEnum());
			this.wrapU = wrapU;
		}
		if(wrapV != null && (force || this.wrapV != wrapV)) {
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_WRAP_T, wrapV.getGlEnum());
			this.wrapV = wrapV;
		}
	}
	
	public static float getMaxAnisotropicFilterLevel() {
		if(Texture.maxAnisotropicFilterLevel > 0) {
			return Texture.maxAnisotropicFilterLevel;
		} else {
			GLCapabilities capabilities = GL.getCapabilities();
			if(capabilities.GL_EXT_texture_filter_anisotropic) {
				float level = GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
				return Texture.maxAnisotropicFilterLevel = level;
			}
		}
		return Texture.maxAnisotropicFilterLevel = 1.0F;
	}
	
	public float getAnisotropicFilterLevel() {
		return anisotropicFilterLevel;
	}
	
	public float setAnisotropicFilterLevel(float level) {
		float max = Texture.getMaxAnisotropicFilterLevel();
		if(max == 1.0F) {
			return 1.0F;
		}
		
		level = Math.min(level, max);
		if(MathUtils.isEqual(level, this.anisotropicFilterLevel, 0.1F)) {
			return level;
		}
		
		this.bind();
		GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, level);
		return this.anisotropicFilterLevel = level;
	}
	
	/**
	 * Sets the anisotropic filter level for the texture. Assumes the texture is bound and active!
	 * @param level The desired level of filtering. The maximum level supported by the device up to this value will be used.
	 * @return The actual level set, which may be lower than the provided value due to device limitations.
	 */
	public float unsafeSetAnisotropicFilterLevel(float level, boolean force) {
		float max = Texture.getMaxAnisotropicFilterLevel();
		if (max == 1.0F) {
			return 1.0F;
		}
		level = Math.min(level, max);
		if (!force && MathUtils.isEqual(level, this.anisotropicFilterLevel, 0.1F))
			return this.anisotropicFilterLevel;
		GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, level);
		return this.anisotropicFilterLevel = level;
	}
	
	public int getTextureID() {
		return textureID;
	}
	
	public int getWidth() {
		return width;
	}
	
	public int getHeight() {
		return height;
	}
	
	public TextureFilter getMinFilter() {
		return minFilter;
	}
	
	public TextureFilter getMagFilter() {
		return magFilter;
	}
	
	public TextureWrap getWrapU() {
		return wrapU;
	}
	
	public TextureWrap getWrapV() {
		return wrapV;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Texture texture = (Texture) o;
		return this.textureID == texture.textureID;
	}

	@Override
	public int hashCode() {
		return Objects.hash(textureID, width, height, minFilter, magFilter, wrapU, wrapV, anisotropicFilterLevel);
	}

	@Override
	public void dispose() {
		if(this.textureID != -1) {
			GL11.glDeleteTextures(this.textureID);	
		}
	}
}
