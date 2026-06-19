package de.skyengine.graphics.gui;

import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.Texture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Zentraler 2D-Overlay-Renderer (ersetzt den alten UIRenderer): zeichnet einfarbige Rechtecke und
 * Texturen/Atlas-Ausschnitte (Sub-Rect-UVs) im GUI-Koordinatenraum. Der Koordinatenraum ist
 * <b>virtuell</b> (vom {@link GuiManager} aus Bildschirmgröße/GUI-Scale berechnet) — dadurch skaliert
 * die gesamte GUI einheitlich. Ursprung oben links, y nach unten.
 */
public final class SpriteRenderer {

    private ShaderProgram shader;
    private int vao, vbo;
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
        int stride = 4 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2 * Float.BYTES);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
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
    }

    public void end() {
        this.shader.unbind();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    public void drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        this.shader.setUniformi("u_UseTexture", 0);
        this.shader.setUniformVector4f("u_Color", r, g, b, a);
        this.draw(x, y, w, h, 0, 0, 1, 1);
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
        if (this.shader != null) this.shader.dispose();
    }

    private static final String VERTEX = """
            #version 460 core
            layout(location = 0) in vec2 a_position;
            layout(location = 1) in vec2 a_uv;
            uniform mat4 u_Projection;
            uniform vec2 u_Position;
            uniform vec2 u_Size;
            uniform vec2 u_UV0;
            uniform vec2 u_UV1;
            out vec2 v_uv;
            void main() {
                v_uv = mix(u_UV0, u_UV1, a_uv);
                vec2 pos = u_Position + a_position * u_Size;
                gl_Position = u_Projection * vec4(pos, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            uniform sampler2D u_Texture;
            uniform vec4 u_Color;
            uniform int u_UseTexture;
            out vec4 fragColor;
            void main() {
                fragColor = u_UseTexture == 1 ? texture(u_Texture, v_uv) * u_Color : u_Color;
            }
            """;
}
