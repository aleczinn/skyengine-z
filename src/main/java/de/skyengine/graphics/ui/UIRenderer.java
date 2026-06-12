package de.skyengine.graphics.ui;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.io.IDisposable;
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
 * Minimaler 2D-Overlay-Renderer. Läuft als letzter Pass im Frame
 * (nach Welt + Selection Box, vor dem Blit). Koordinatensystem:
 * Pixel, Ursprung oben links.
 */
public class UIRenderer implements IDisposable {

    private ShaderProgram shader;
    private int vao, vbo;

    private Texture crosshairTexture;
    private static final float CROSSHAIR_SIZE = 16.0F; // Pixel auf dem Bildschirm

    private final Matrix4f ortho = new Matrix4f();

    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT)
        );

        this.crosshairTexture = new Texture(
                SkyEngine.get().getFiles().absolute("./src/main/resources/game/texture/ui/crosshair_dot.png")
        );

        /* Einheits-Quad (0..1) mit UVs - Position & Größe kommen als Uniforms.
           Reicht für einzelne UI-Elemente; ein SpriteBatch kommt später mit der Hotbar. */
        float[] vertices = {
                // x, y,  u, v
                0, 0,  0, 0,
                0, 1,  0, 1,
                1, 1,  1, 1,
                1, 1,  1, 1,
                1, 0,  1, 0,
                0, 0,  0, 0
        };

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);

        int stride = 4 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);               // position
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2 * Float.BYTES); // uv
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL30.glBindVertexArray(0);
    }

    /** Einmal pro Frame nach dem Welt-Rendering aufrufen. */
    public void render(int screenWidth, int screenHeight) {
        /* UI-State: kein Depth, mit Blending */
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);

        /* Pixel-Koordinaten, Ursprung oben links */
        this.ortho.setOrtho2D(0, screenWidth, screenHeight, 0);

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_Projection", this.ortho);
        this.shader.setUniformi("u_Texture", 0);

        /* --- Crosshair, zentriert --- */
        this.drawTexture(
                this.crosshairTexture,
                (screenWidth - CROSSHAIR_SIZE) / 2.0F,
                (screenHeight - CROSSHAIR_SIZE) / 2.0F,
                CROSSHAIR_SIZE, CROSSHAIR_SIZE
        );

        this.shader.unbind();

        /* State für den nächsten Frame zurücksetzen */
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /** Zeichnet eine Textur an Pixelposition (x, y) mit gegebener Größe. */
    public void drawTexture(Texture texture, float x, float y, float width, float height) {
        this.shader.setUniformVector2f("u_Position", x, y);
        this.shader.setUniformVector2f("u_Size", width, height);

        texture.bind(0);

        GL30.glBindVertexArray(this.vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
    }

    @Override
    public void dispose() {
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        if (this.crosshairTexture != null) this.crosshairTexture.dispose();
        if (this.shader != null) this.shader.dispose();
    }

    private static final String VERTEX = """
            #version 460 core
            layout(location = 0) in vec2 a_position;
            layout(location = 1) in vec2 a_texCoord;

            uniform mat4 u_Projection;
            uniform vec2 u_Position;
            uniform vec2 u_Size;

            out vec2 v_texCoord;

            void main() {
                v_texCoord = a_texCoord;
                vec2 pos = u_Position + a_position * u_Size;
                gl_Position = u_Projection * vec4(pos, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 460 core
            in vec2 v_texCoord;

            uniform sampler2D u_Texture;

            out vec4 fragColor;

            void main() {
                fragColor = texture(u_Texture, v_texCoord);
            }
            """;
}