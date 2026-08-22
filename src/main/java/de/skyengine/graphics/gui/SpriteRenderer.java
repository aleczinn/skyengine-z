package de.skyengine.graphics.gui;

import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.Texture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * Zentraler 2D-Overlay-Renderer (ersetzt den alten UIRenderer): zeichnet einfarbige Rechtecke und
 * Texturen/Atlas-Ausschnitte (Sub-Rect-UVs) im GUI-Koordinatenraum. Der Koordinatenraum ist
 * <b>virtuell</b> (vom {@link GuiManager} aus Bildschirmgröße/GUI-Scale berechnet) — dadurch skaliert
 * die gesamte GUI einheitlich. Ursprung oben links, y nach unten.
 */
public final class SpriteRenderer {

    private ShaderProgram shader;
    private int vao, vbo;
    private int rectBatchVao, rectBatchVbo;
    private final Matrix4f ortho = new Matrix4f();

    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));

        float[] quad = {
                0, 0, 0, 0,
                0, 1, 0, 1,
                1, 1, 1, 1,
                1, 1, 1, 1,
                1, 0, 1, 0,
                0, 0, 0, 0,
        };
        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
        GlDebug.labelBuffer(this.vbo, "SpriteRenderer Quad-VBO");
        int stride = 4 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2 * Float.BYTES);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL30.glBindVertexArray(0);

        this.rectBatchVao = GL30.glGenVertexArrays();
        this.rectBatchVbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.rectBatchVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.rectBatchVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 0, GL15.GL_STREAM_DRAW);
        int batchStride = 6 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, batchStride, 0);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, batchStride, 2 * Float.BYTES);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(2);
        GL30.glBindVertexArray(0);
    }

    /** Startet einen 2D-Pass im virtuellen Koordinatenraum (vW × vH). */
    public void begin(float vW, float vH) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        this.ortho.setOrtho2D(0, vW, vH, 0);
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_Projection", this.ortho);
        this.shader.setUniformi("u_Texture", 0);
        this.shader.setUniformi("u_UseVertexColor", 0);
    }

    public void end() {
        this.shader.unbind();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    public void drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        this.shader.setUniformi("u_UseVertexColor", 0);
        this.shader.setUniformi("u_UseTexture", 0);
        this.shader.setUniformVector4f("u_Color", r, g, b, a);
        this.draw(x, y, w, h, 0, 0, 1, 1);
    }

    /** Viele einfarbige Rechtecke in einem Draw Call; fuer Profiler-Graphen und Raster. */
    public void drawRects(List<Rect> rects) {
        if (rects.isEmpty()) return;
        float[] vertices = new float[rects.size() * 6 * 6];
        int at = 0;
        for (Rect rect : rects) {
            float x0 = rect.x, y0 = rect.y, x1 = rect.x + rect.width, y1 = rect.y + rect.height;
            at = putVertex(vertices, at, x0, y0, rect);
            at = putVertex(vertices, at, x0, y1, rect);
            at = putVertex(vertices, at, x1, y1, rect);
            at = putVertex(vertices, at, x1, y1, rect);
            at = putVertex(vertices, at, x1, y0, rect);
            at = putVertex(vertices, at, x0, y0, rect);
        }
        this.shader.setUniformi("u_UseTexture", 0);
        this.shader.setUniformi("u_UseVertexColor", 1);
        /* Der Fragmentshader multipliziert Vertex- und Uniformfarbe. drawRect() kann zuvor
           Schwarz gesetzt haben (Profiler-Hintergrund); ohne Weiss hier wird der ganze Batch
           dadurch schwarz, obwohl seine Vertices korrekte Farben tragen. */
        this.shader.setUniformVector4f("u_Color", 1F, 1F, 1F, 1F);
        this.shader.setUniformVector2f("u_Position", 0, 0);
        this.shader.setUniformVector2f("u_Size", 1, 1);
        GL30.glBindVertexArray(this.rectBatchVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.rectBatchVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, rects.size() * 6);
        GL30.glBindVertexArray(0);
        this.shader.setUniformi("u_UseVertexColor", 0);
    }

    public record Rect(float x, float y, float width, float height,
                       float red, float green, float blue, float alpha) {}

    private static int putVertex(float[] vertices, int at, float x, float y, Rect rect) {
        vertices[at++] = x; vertices[at++] = y;
        vertices[at++] = rect.red; vertices[at++] = rect.green;
        vertices[at++] = rect.blue; vertices[at++] = rect.alpha;
        return at;
    }

    public void drawSprite(Texture texture, float x, float y, float w, float h) {
        this.drawSprite(texture, x, y, w, h, 0, 0, 1, 1);
    }

    /** Zeichnet einen Texturausschnitt [u0,v0]..[u1,v1] (normalisiert) als Quad. */
    public void drawSprite(Texture texture, float x, float y, float w, float h,
                           float u0, float v0, float u1, float v1) {
        this.shader.setUniformi("u_UseTexture", 1);
        this.shader.setUniformVector4f("u_Color", 1f, 1f, 1f, 1f);
        texture.bind(0);
        this.draw(x, y, w, h, u0, v0, u1, v1);
    }

    /**
     * Zeichnet eine Textur als 9-Slice: Ecken unskaliert ({@code border} Texel), Kanten in eine
     * Richtung gestreckt, Mitte in beide — für Buttons/Slider/Textfelder in beliebiger Größe
     * (MC-Widget-Sprites nutzen Rand 3). Erwartet 1 Texel = 1 virtueller Pixel.
     */
    public void drawNineSlice(Texture texture, float x, float y, float w, float h, int border) {
        float tw = texture.getWidth(), th = texture.getHeight();
        float b = border;
        float u1 = b / tw, u2 = (tw - b) / tw;
        float v1 = b / th, v2 = (th - b) / th;
        float mw = w - 2 * b, mh = h - 2 * b; // Innenmaße (Kanten/Mitte)

        this.shader.setUniformi("u_UseTexture", 1);
        this.shader.setUniformVector4f("u_Color", 1f, 1f, 1f, 1f);
        texture.bind(0);

        /* Ecken */
        this.draw(x, y, b, b, 0, 0, u1, v1);
        this.draw(x + w - b, y, b, b, u2, 0, 1, v1);
        this.draw(x, y + h - b, b, b, 0, v2, u1, 1);
        this.draw(x + w - b, y + h - b, b, b, u2, v2, 1, 1);
        /* Kanten */
        if (mw > 0) {
            this.draw(x + b, y, mw, b, u1, 0, u2, v1);
            this.draw(x + b, y + h - b, mw, b, u1, v2, u2, 1);
        }
        if (mh > 0) {
            this.draw(x, y + b, b, mh, 0, v1, u1, v2);
            this.draw(x + w - b, y + b, b, mh, u2, v1, 1, v2);
        }
        /* Mitte */
        if (mw > 0 && mh > 0) {
            this.draw(x + b, y + b, mw, mh, u1, v1, u2, v2);
        }
    }

    private void draw(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
        this.shader.setUniformVector2f("u_Position", x, y);
        this.shader.setUniformVector2f("u_Size", w, h);
        this.shader.setUniformVector2f("u_UV0", u0, v0);
        this.shader.setUniformVector2f("u_UV1", u1, v1);
        GL30.glBindVertexArray(this.vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
    }

    public void dispose() {
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        GL30.glDeleteVertexArrays(this.rectBatchVao);
        GL15.glDeleteBuffers(this.rectBatchVbo);
        if (this.shader != null) this.shader.dispose();
    }

    private static final String VERTEX = """
            #version 460 core
            layout(location = 0) in vec2 a_position;
            layout(location = 1) in vec2 a_uv;
            layout(location = 2) in vec4 a_color;
            uniform mat4 u_Projection;
            uniform vec2 u_Position;
            uniform vec2 u_Size;
            uniform vec2 u_UV0;
            uniform vec2 u_UV1;
            uniform int u_UseVertexColor;
            out vec2 v_uv;
            out vec4 v_color;
            void main() {
                v_uv = mix(u_UV0, u_UV1, a_uv);
                vec2 pos = u_Position + a_position * u_Size;
                gl_Position = u_Projection * vec4(pos, 0.0, 1.0);
                v_color = u_UseVertexColor == 1 ? a_color : vec4(1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            in vec4 v_color;
            uniform sampler2D u_Texture;
            uniform vec4 u_Color;
            uniform int u_UseTexture;
            out vec4 fragColor;
            void main() {
                fragColor = (u_UseTexture == 1 ? texture(u_Texture, v_uv) * u_Color : u_Color) * v_color;
            }
            """;
}
