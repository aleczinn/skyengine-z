package de.skyengine.graphics.gui.font;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.Files;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.graphics.gui.text.Span;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Gebatchter Text-Renderer im virtuellen GUI-Koordinatenraum (Ursprung oben links, y nach
 * unten — wie {@link de.skyengine.graphics.gui.SpriteRenderer}). Pro Stil ({@link FontStyle})
 * ein {@link FontAtlas}; die Familie ist über {@link #FAMILY} festgelegt.
 *
 * <ul>
 *   <li><b>Batching:</b> alle drawString-Aufrufe zwischen {@code begin}/{@code end} sammeln
 *       Glyph-Quads in einem dynamischen VBO; geflusht wird bei {@code end()}, Stil-Wechsel
 *       (anderer Atlas = andere Textur) oder vollem Puffer → typisch 1–2 Draw-Calls/Frame.</li>
 *   <li><b>Fehlende Fonts:</b> fehlt ein Stil, wird Regular verwendet; fehlt auch Regular,
 *       ist {@link #available()} false und alle draw-Methoden sind No-Ops (kein Crash).</li>
 *   <li>{@code (x, y)} ist immer die OBERE LINKE Ecke der Textzeile; {@code size} die
 *       Zielhöhe (Zeilenmaß) in virtuellen Pixeln.</li>
 * </ul>
 */
public final class FontRenderer {

    /** Font-Familie in {@code game/fonts/} — Alternative im Repo: "silkscreen". */
    private static final String FAMILY = "monocraft";
    /** Scherung für simuliertes Kursiv, wenn kein italic-Schnitt vorliegt (MC-Look). */
    private static final float ITALIC_SHEAR = 0.2f;
    private static final int MAX_GLYPHS = 2048;
    private static final int FLOATS_PER_VERTEX = 8; // x,y | u,v | r,g,b,a
    private static final int FLOATS_PER_GLYPH = 6 * FLOATS_PER_VERTEX;

    private final Logger logger = LogManager.getLogger(FontRenderer.class.getName());

    private final FontAtlas[] atlases = new FontAtlas[FontStyle.values().length];
    private ShaderProgram shader;
    private int vao, vbo;
    private final Matrix4f ortho = new Matrix4f();

    private FloatBuffer batch;
    private int batchGlyphs;
    private FontAtlas batchAtlas;
    private boolean drawing;

    /* Scratch für stbtt_GetPackedQuad — einmal alloziert statt pro Glyph. */
    private FloatBuffer xpos, ypos;
    private STBTTAlignedQuad quad;

    /** Lädt die Atlanten und baut Shader/VAO. Nur auf dem Render-Thread (GL-Kontext). */
    public void init() {
        Files files = SkyEngine.get().getFiles();
        FileHandle regular = files.resource("game/fonts/" + FAMILY + FontStyle.REGULAR.suffix + ".ttf");
        if (!regular.exists()) regular = files.resource("game/fonts/" + FAMILY + ".ttf");
        this.atlases[FontStyle.REGULAR.ordinal()] = FontAtlas.load(regular);
        if (!this.available()) {
            this.logger.warning("Kein Font gefunden (game/fonts/" + FAMILY + "[-regular].ttf) — Text-Rendering deaktiviert.");
            return;
        }

        for (FontStyle style : FontStyle.values()) {
            if (style == FontStyle.REGULAR) continue;
            FileHandle file = files.resource("game/fonts/" + FAMILY + style.suffix + ".ttf");
            this.atlases[style.ordinal()] = FontAtlas.load(file);
            if (this.atlases[style.ordinal()] == null) {
                this.logger.warning("Font-Stil " + style + " fehlt (" + file.name() + ") — Fallback auf REGULAR.");
            }
        }

        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));

        this.batch = MemoryUtil.memAllocFloat(MAX_GLYPHS * FLOATS_PER_GLYPH);
        this.xpos = MemoryUtil.memAllocFloat(1);
        this.ypos = MemoryUtil.memAllocFloat(1);
        this.quad = STBTTAlignedQuad.malloc();

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_GLYPHS * FLOATS_PER_GLYPH * Float.BYTES, GL15.GL_STREAM_DRAW);
        GlDebug.labelBuffer(this.vbo, "FontRenderer Batch-VBO");
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2 * Float.BYTES);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 4 * Float.BYTES);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glEnableVertexAttribArray(2);
        GL30.glBindVertexArray(0);
    }

    /** true, wenn mindestens der Regular-Atlas geladen wurde. */
    public boolean available() {
        return this.atlases[FontStyle.REGULAR.ordinal()] != null;
    }

    /** Startet einen Text-Pass im virtuellen Koordinatenraum (vW × vH). */
    public void begin(float vW, float vH) {
        if (!this.available()) return;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        this.ortho.setOrtho2D(0, vW, vH, 0);
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_Projection", this.ortho);
        this.shader.setUniformi("u_Texture", 0);
        this.drawing = true;
    }

    public void end() {
        if (!this.drawing) return;
        this.flush();
        this.batchAtlas = null;
        this.drawing = false;
        this.shader.unbind();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    public void drawString(String text, float x, float y, float size, Color4 color) {
        this.drawString(text, x, y, size, FontStyle.REGULAR, color);
    }

    public void drawString(String text, float x, float y, float size, FontStyle style, Color4 color) {
        this.drawString(text, x, y, size, style, color.red, color.green, color.blue, color.alpha);
    }

    public void drawStringWithShadow(String text, float x, float y, float size, Color4 color) {
        this.drawStringWithShadow(text, x, y, size, FontStyle.REGULAR, color);
    }

    /** Minecraft-Look: erst abgedunkelte Kopie (rgb·0.25) mit Offset +size/8, dann der Text. */
    public void drawStringWithShadow(String text, float x, float y, float size, FontStyle style, Color4 color) {
        float offset = size / 8.0F;
        this.drawString(text, x + offset, y + offset, size, style,
                color.red * 0.25F, color.green * 0.25F, color.blue * 0.25F, color.alpha);
        this.drawString(text, x, y, size, style, color.red, color.green, color.blue, color.alpha);
    }

    private void drawString(String text, float x, float y, float size, FontStyle style,
                            float r, float g, float b, float a) {
        if (!this.drawing || text == null || text.isEmpty()) return;

        FontAtlas atlas = this.atlasFor(style);
        if (atlas != this.batchAtlas) {
            this.flush();
            this.batchAtlas = atlas;
        }

        float scale = size / FontAtlas.BAKE_PX;
        float baseline = y + atlas.ascent() * scale;
        /* Kursiv ohne eigenen Font-Schnitt: Scherung um die Grundlinie (macht Minecraft genauso).
           Liegt eine echte italic.ttf vor, bleibt der Text ungeschert. Bei allen anderen Stilen
           ist der Faktor 0 -> die Vertices sind bitgleich zu vorher. */
        float shear = style == FontStyle.ITALIC && this.atlases[FontStyle.ITALIC.ordinal()] == null
                ? ITALIC_SHEAR : 0f;
        this.xpos.put(0, 0.0F);
        this.ypos.put(0, 0.0F);

        for (int i = 0; i < text.length(); i++) {
            atlas.getQuad(text.charAt(i), this.xpos, this.ypos, this.quad);
            float x0 = x + this.quad.x0() * scale;
            float x1 = x + this.quad.x1() * scale;
            if (x1 <= x0) continue; // unsichtbar (z.B. Leerzeichen) — Advance ist schon passiert
            float y0 = baseline + this.quad.y0() * scale;
            float y1 = baseline + this.quad.y1() * scale;
            /* Versatz pro Ecke: oben (über der Grundlinie) nach rechts, unten nach links. */
            float topShift = (baseline - y0) * shear;
            float bottomShift = (baseline - y1) * shear;

            if (this.batchGlyphs == MAX_GLYPHS) this.flush();
            float u0 = this.quad.s0(), v0 = this.quad.t0();
            float u1 = this.quad.s1(), v1 = this.quad.t1();
            this.vertex(x0 + topShift, y0, u0, v0, r, g, b, a);
            this.vertex(x0 + bottomShift, y1, u0, v1, r, g, b, a);
            this.vertex(x1 + bottomShift, y1, u1, v1, r, g, b, a);
            this.vertex(x1 + bottomShift, y1, u1, v1, r, g, b, a);
            this.vertex(x1 + topShift, y0, u1, v0, r, g, b, a);
            this.vertex(x0 + topShift, y0, u0, v0, r, g, b, a);
            this.batchGlyphs++;
        }
    }

    /**
     * Formatierten Text zeichnen (Segmente aus {@link RichText}). Schatten läuft als eigener
     * Vorlauf über ALLE Segmente — sonst legt sich der Schatten des nächsten Segments über die
     * Glyphen des vorherigen.
     *
     * @param base Farbe für Segmente ohne eigene Farbe
     */
    public void drawRich(RichText text, float x, float y, float size, Color4 base, boolean shadow) {
        if (!this.available() || text == null || text.isEmpty()) return;
        if (shadow) {
            float offset = size / 8.0F;
            float sx = x + offset;
            for (Span span : text.spans()) {
                Color4 c = span.color() != null ? span.color() : base;
                this.drawString(span.text(), sx, y + offset, size, span.style(),
                        c.red * 0.25F, c.green * 0.25F, c.blue * 0.25F, c.alpha);
                sx += this.getStringWidth(span.text(), size, span.style());
            }
        }
        float cx = x;
        for (Span span : text.spans()) {
            Color4 c = span.color() != null ? span.color() : base;
            this.drawString(span.text(), cx, y, size, span.style(), c.red, c.green, c.blue, c.alpha);
            cx += this.getStringWidth(span.text(), size, span.style());
        }
    }

    /** Breite eines formatierten Textes (Segmentbreiten summiert — die Schrift kennt kein Kerning). */
    public float width(RichText text, float size) {
        if (!this.available() || text == null) return 0;
        float width = 0;
        for (Span span : text.spans()) {
            width += this.getStringWidth(span.text(), size, span.style());
        }
        return width;
    }

    private void vertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        this.batch.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    public float getStringWidth(String text, float size) {
        return this.getStringWidth(text, size, FontStyle.REGULAR);
    }

    public float getStringWidth(String text, float size, FontStyle style) {
        if (!this.available() || text == null) return 0;
        FontAtlas atlas = this.atlasFor(style);
        float width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += atlas.advance(text.charAt(i));
        }
        return width * (size / FontAtlas.BAKE_PX);
    }

    /** Zeilenhöhe (ascent − descent + lineGap) bei gegebener Zielgröße. */
    public float lineHeight(float size) {
        if (!this.available()) return 0;
        return this.atlasFor(FontStyle.REGULAR).lineHeight() * (size / FontAtlas.BAKE_PX);
    }

    private FontAtlas atlasFor(FontStyle style) {
        FontAtlas atlas = this.atlases[style.ordinal()];
        return atlas != null ? atlas : this.atlases[FontStyle.REGULAR.ordinal()];
    }

    /** Lädt den gesammelten Batch hoch und zeichnet ihn mit dem aktuellen Atlas. */
    private void flush() {
        if (this.batchGlyphs == 0) {
            this.batch.clear();
            return;
        }
        this.batch.flip();
        this.batchAtlas.bind(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, this.batch);
        GL30.glBindVertexArray(this.vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.batchGlyphs * 6);
        GL30.glBindVertexArray(0);
        this.batch.clear();
        this.batchGlyphs = 0;
    }

    public void dispose() {
        for (FontAtlas atlas : this.atlases) {
            if (atlas != null) atlas.dispose();
        }
        if (this.shader != null) this.shader.dispose();
        if (this.vao != 0) GL30.glDeleteVertexArrays(this.vao);
        if (this.vbo != 0) GL15.glDeleteBuffers(this.vbo);
        if (this.batch != null) MemoryUtil.memFree(this.batch);
        if (this.xpos != null) MemoryUtil.memFree(this.xpos);
        if (this.ypos != null) MemoryUtil.memFree(this.ypos);
        if (this.quad != null) this.quad.free();
    }

    private static final String VERTEX = """
            #version 460 core
            layout(location = 0) in vec2 a_position;
            layout(location = 1) in vec2 a_uv;
            layout(location = 2) in vec4 a_color;
            uniform mat4 u_Projection;
            out vec2 v_uv;
            out vec4 v_color;
            void main() {
                v_uv = a_uv;
                v_color = a_color;
                gl_Position = u_Projection * vec4(a_position, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            in vec4 v_color;
            uniform sampler2D u_Texture;
            out vec4 fragColor;
            void main() {
                // GL_R8-Atlas: Glyph-Deckung steckt im Rot-Kanal
                fragColor = vec4(v_color.rgb, v_color.a * texture(u_Texture, v_uv).r);
            }
            """;
}
