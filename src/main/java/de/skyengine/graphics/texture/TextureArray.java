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
	private final int size;

	public TextureArray(int size, String[] paths) {
		this(size, paths, java.util.Set.of());
	}

	/**
	 * @param size        Kantenlänge aller (statischen) Texturen (z.B. 16)
	 * @param paths       Pfade relativ zum resources-Ordner, Index = Layer im Array
	 * @param skipLayers  Layer animierter Texturen: NICHT statisch laden, werden vom
	 *                    {@link SpriteAnimations}-System pro Frame befüllt.
	 */
	public TextureArray(int size, String[] paths, java.util.Set<Integer> skipLayers) {
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
				String fullPath = Files.RESOURCES_PATH + paths[layer];
				ByteBuffer pixels = STBImage.stbi_load(fullPath, w, h, c, 4);

				if (pixels == null || w.get(0) != size || h.get(0) != size) {
					if (pixels != null) STBImage.stbi_image_free(pixels);
					this.logger.warning("Textur fehlt oder falsche Größe (" + size + "x" + size + " erwartet): " + paths[layer]);
					pixels = missingTexture(size);
					GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
					MemoryUtil.memFree(pixels);
				} else {
					/* Alpha-Bleed-Fix: transparente Pixel bekommen die RGB-Farbe
					   ihres nächsten sichtbaren Nachbarn, damit die Mipmap-Mittlung
					   keine fremde (oft weiße) Farbe einrechnet. */
					bleedAlpha(pixels, size, size);
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

		de.skyengine.graphics.GlDebug.labelTexture(this.id, "Block-TextureArray (" + paths.length + " Layer)");
		this.logger.info("TextureArray erstellt: " + paths.length + " Layer à " + size + "x" + size);
	}

	/**
	 * Behebt "Alpha Bleeding": Transparente Pixel (alpha == 0) tragen ihre RGB-Werte
	 * nicht zur Mipmap-Mittelung bei, weil sie unsichtbar sind - aber die GPU mittelt
	 * sie trotzdem mit. Viele PNGs speichern in transparenten Pixeln Weiß oder Schwarz,
	 * was an Cutout-Kanten als heller/dunkler Saum sichtbar wird.
	 * <p>
	 * Fix: Jeder transparente Pixel kopiert die RGB-Farbe seines nächsten
	 * undurchsichtigen Nachbarn. Alpha bleibt 0 (also weiterhin unsichtbar),
	 * aber die RGB-Mittelung ergibt jetzt die korrekte Randfarbe.
	 * <p>
	 * Iterativer Flood-Fill: in mehreren Durchläufen breitet sich die Farbe
	 * von den Kanten in die transparenten Flächen aus.
	 *
	 * @param pixels RGBA-Buffer, 4 Bytes pro Pixel (wird in-place verändert)
	 * @param width  Texturbreite
	 * @param height Texturhöhe
	 */
	private static void bleedAlpha(ByteBuffer pixels, int width, int height) {
		/* Arbeitskopie der Alpha-Flags: true = Pixel hat eine gültige Farbe (sichtbar
		   oder bereits gefüllt). Wir füllen iterativ, bis nichts mehr offen ist. */
		boolean[] filled = new boolean[width * height];
		for (int i = 0; i < width * height; i++) {
			int alpha = pixels.get(i * 4 + 3) & 0xFF;
			filled[i] = alpha != 0;
		}

		/* 4er-Nachbarschaft (oben, unten, links, rechts) */
		int[] offX = {0, 0, -1, 1};
		int[] offY = {-1, 1, 0, 0};

		boolean changed = true;
		/* Maximal so viele Durchläufe wie die längste Kante - dann ist garantiert
		   jeder transparente Pixel erreicht (bei 16x16 also höchstens 16 Runden). */
		int maxPasses = Math.max(width, height);

		for (int pass = 0; pass < maxPasses && changed; pass++) {
			changed = false;

			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int index = y * width + x;
					if (filled[index]) continue;

					/* Suche einen bereits gefüllten Nachbarn und übernimm dessen RGB */
					for (int n = 0; n < 4; n++) {
						int nx = x + offX[n];
						int ny = y + offY[n];
						if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;

						int neighbor = ny * width + nx;
						if (!filled[neighbor]) continue;

						/* RGB des Nachbarn kopieren, Alpha (dieses Pixels) bleibt 0 */
						pixels.put(index * 4,     pixels.get(neighbor * 4));     // r
						pixels.put(index * 4 + 1, pixels.get(neighbor * 4 + 1)); // g
						pixels.put(index * 4 + 2, pixels.get(neighbor * 4 + 2)); // b
						/* Alpha NICHT anfassen - bleibt transparent */

						filled[index] = true;
						changed = true;
						break;
					}
				}
			}
		}
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

	/**
	 * Mipmaps neu erzeugen — nötig nachdem animierte Layer ihre Initial-Frames erhalten haben
	 * (sonst bleiben deren Mip-Level transparent → Fluids faden in der Ferne aus).
	 */
	public void regenerateMipmaps() {
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
		GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
	}

	/**
	 * Ersetzt den Inhalt eines Layers (Basis-Mip). Für animierte Sprites pro Frame.
	 * Mip-Level werden nicht neu erzeugt — bei animierten Blöcken in der Ferne minimal
	 * unscharf; für Lava/Wasser vernachlässigbar.
	 */
	public void updateLayer(int layer, ByteBuffer rgba) {
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
		GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer,
				this.size, this.size, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
	}

	public void bind(int unit) {
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
	}

	public void dispose() {
		GL11.glDeleteTextures(this.id);
	}
}