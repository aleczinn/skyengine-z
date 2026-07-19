package de.skyengine.graphics.gui.font;

import de.skyengine.core.file.FileHandle;
import de.skyengine.graphics.GlDebug;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.stb.STBTruetype.*;

/**
 * Ein per stb_truetype gebackener Glyph-Atlas für EINEN Schriftstil: lädt die TTF-Datei,
 * packt die Glyphen (mit Oversampling) in eine {@code GL_R8}-Textur und liefert danach
 * Quad-/Metrik-Daten für den {@link FontRenderer}.
 *
 * <ul>
 *   <li>Gebacken wird genau EINE Größe ({@link #BAKE_PX}); Zielgrößen entstehen beim
 *       Zeichnen durch Skalierung (Oversampling 2×2 hält das Downscaling sauber).</li>
 *   <li>Zeichenumfang {@link #FIRST_CP}..{@link #LAST_CP} (ASCII + Latin-1: ä ö ü ß § …);
 *       alles außerhalb fällt auf {@code '?'} zurück.</li>
 *   <li>Nur auf dem Render-Thread laden (GL-Kontext nötig). Der TTF-Puffer wird nach dem
 *       Bake sofort freigegeben; die {@link STBTTPackedchar}-Metriken leben bis {@link #dispose()},
 *       weil {@code stbtt_GetPackedQuad} sie pro Glyph braucht.</li>
 * </ul>
 */
public final class FontAtlas {

    /** Bake-Größe in Pixeln — Zielgrößen skalieren mit {@code size / BAKE_PX}. */
    public static final float BAKE_PX = 48.0F;
    public static final int FIRST_CP = 0x20;
    public static final int LAST_CP = 0xFF;
    private static final int GLYPH_COUNT = LAST_CP - FIRST_CP + 1;
    private static final int ATLAS_SIZE = 1024;

    private static final Logger LOGGER = LogManager.getLogger(FontAtlas.class.getName());

    private final int textureId;
    private final STBTTPackedchar.Buffer chars;
    /* Vertikale Metriken, bereits auf BAKE_PX skaliert (descent ist negativ). */
    private final float ascent;
    private final float descent;
    private final float lineGap;

    private FontAtlas(int textureId, STBTTPackedchar.Buffer chars, float ascent, float descent, float lineGap) {
        this.textureId = textureId;
        this.chars = chars;
        this.ascent = ascent;
        this.descent = descent;
        this.lineGap = lineGap;
    }

    /**
     * Lädt und backt die TTF-Datei; {@code null} bei jedem Fehler (Datei fehlt, kein gültiges
     * TTF, Atlas zu klein) — der Aufrufer entscheidet über Fallback/Warnung.
     */
    public static FontAtlas load(FileHandle file) {
        if (!file.exists()) return null;

        ByteBuffer ttf = null;
        ByteBuffer bitmap = null;
        STBTTPackedchar.Buffer chars = null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.getFile().toPath());
            ttf = MemoryUtil.memAlloc(bytes.length);
            ttf.put(bytes).flip();

            /* Vertikale Metriken aus der Fontinfo (nur während des Ladens gebraucht). */
            float ascent, descent, lineGap;
            STBTTFontinfo info = STBTTFontinfo.malloc();
            try {
                if (!stbtt_InitFont(info, ttf)) {
                    LOGGER.warning("Font " + file.name() + " ist kein gültiges TTF/OTF.");
                    return null;
                }
                float scale = stbtt_ScaleForPixelHeight(info, BAKE_PX);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer a = stack.mallocInt(1);
                    IntBuffer d = stack.mallocInt(1);
                    IntBuffer g = stack.mallocInt(1);
                    stbtt_GetFontVMetrics(info, a, d, g);
                    ascent = a.get(0) * scale;
                    descent = d.get(0) * scale;
                    lineGap = g.get(0) * scale;
                }
            } finally {
                info.free();
            }

            /* Glyphen packen: 1-Kanal-Bitmap, 1 px Padding gegen Filter-Bleeding. */
            bitmap = MemoryUtil.memAlloc(ATLAS_SIZE * ATLAS_SIZE);
            chars = STBTTPackedchar.malloc(GLYPH_COUNT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                STBTTPackContext ctx = STBTTPackContext.malloc(stack);
                if (!stbtt_PackBegin(ctx, bitmap, ATLAS_SIZE, ATLAS_SIZE, 0, 1)) {
                    LOGGER.warning("stbtt_PackBegin für " + file.name() + " fehlgeschlagen.");
                    chars.free();
                    return null;
                }
                stbtt_PackSetOversampling(ctx, 2, 2);
                boolean packed = stbtt_PackFontRange(ctx, ttf, 0, BAKE_PX, FIRST_CP, chars);
                stbtt_PackEnd(ctx);
                if (!packed) {
                    LOGGER.warning("Glyph-Atlas (" + ATLAS_SIZE + "²) für " + file.name() + " zu klein — Font wird ignoriert.");
                    chars.free();
                    return null;
                }
            }

            /* Upload als GL_R8 (Alpha steckt im Rot-Kanal, Shader liest .r). */
            int textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, ATLAS_SIZE, ATLAS_SIZE, 0,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, bitmap);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4); // Default wiederherstellen
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GlDebug.labelTexture(textureId, "FontAtlas " + file.name());

            FontAtlas atlas = new FontAtlas(textureId, chars, ascent, descent, lineGap);
            chars = null; // Ownership an den Atlas übergeben (finally darf nicht mehr freigeben)
            return atlas;
        } catch (Exception e) {
            LOGGER.warning("Font " + file.name() + " konnte nicht geladen werden.", e);
            if (chars != null) chars.free();
            return null;
        } finally {
            if (bitmap != null) MemoryUtil.memFree(bitmap);
            if (ttf != null) MemoryUtil.memFree(ttf);
        }
    }

    public void bind(int unit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
    }

    /**
     * Füllt {@code q} mit dem Quad des Codepoints (Positionen relativ zur Baseline, in
     * BAKE_PX-Einheiten) und rückt {@code xpos} um den Advance vor. Unbekannte Codepoints → '?'.
     */
    public void getQuad(int codepoint, FloatBuffer xpos, FloatBuffer ypos, STBTTAlignedQuad q) {
        stbtt_GetPackedQuad(this.chars, ATLAS_SIZE, ATLAS_SIZE, index(codepoint), xpos, ypos, q, false);
    }

    /** Horizontaler Vorschub des Codepoints in BAKE_PX-Einheiten. */
    public float advance(int codepoint) {
        return this.chars.get(index(codepoint)).xadvance();
    }

    private static int index(int codepoint) {
        if (codepoint < FIRST_CP || codepoint > LAST_CP) codepoint = '?';
        return codepoint - FIRST_CP;
    }

    /** Abstand Oberkante→Baseline in BAKE_PX-Einheiten. */
    public float ascent() {
        return this.ascent;
    }

    /** Zeilenhöhe (ascent − descent + lineGap) in BAKE_PX-Einheiten. */
    public float lineHeight() {
        return this.ascent - this.descent + this.lineGap;
    }

    public void dispose() {
        GL11.glDeleteTextures(this.textureId);
        this.chars.free();
    }
}
