package de.skyengine.graphics.world;

import de.skyengine.core.EngineProperties;
import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;
import de.skyengine.game.world.structure.StructureBounds;

/**
 * Debug-Overlay der Chunk-Grenzen (F3+G), Vorbild {@link SelectionBoxRenderer}: dünne
 * {@code GL_LINES}-Boxen um die ~3x3 Chunks um den Spieler. Modus 1 = ganzer Chunk (gelb),
 * Modus 2 = zusätzlich jede nicht-leere Section (cyan). Positionen werden kamerarelativ auf der
 * CPU gebacken (die View-Matrix ist translationsfrei) und in zwei Draws pro Farbe hochgeladen.
 */
public class ChunkBorderRenderer {

    /** Wie viele Chunks in jede Richtung um den Spieler (1 => 3x3, wie MCs „2-3 Chunks"). */
    private static final int RADIUS = 1;

    private ShaderProgram shader;
    private int vao, vbo;

    /* Wächst bei Bedarf; wird pro Farb-Draw befüllt und geleert. */
    private float[] buf = new float[6 * 24];
    private int count;

    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(GEOMETRY, ShaderType.GEOMETRY),
                new Shader(FRAGMENT, ShaderType.FRAGMENT)
        );

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.buf.length * 4L, GL15.GL_DYNAMIC_DRAW);
        GlDebug.labelBuffer(this.vbo, "ChunkBorder VBO (Streaming)");
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    /** centerCX/CZ = Chunk-Koordinaten des Spielers; mode 1 = Chunk, 2 = Chunk + Sections. */
    public void render(Camera camera, ChunkManager chunks, int centerCX, int centerCZ, int mode) {
        if (mode <= 0) return;
        Vector3d cam = camera.getPosition();

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformVector2f("u_Viewport",
                SkyEngine.get().getWindow().getWidth(),
                SkyEngine.get().getWindow().getHeight());
        this.shader.setUniformf("u_LineWidth", 2.0F);

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL11.glEnable(GL11.GL_BLEND);

        /* Reversed-Z wie SelectionBox: or-equal-Depth-Func, Tiefen-Bias im Vertex-Shader. */
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.orEqualDepthFunc());

        int size = ChunkSection.SIZE;

        /* Ganze Chunk-Säulen (gelb). */
        this.count = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int ox = (centerCX + dx) << ChunkSection.SHIFT;
                int oz = (centerCZ + dz) << ChunkSection.SHIFT;
                box(cam, ox, 0, oz, ox + size, Chunk.HEIGHT, oz + size);
            }
        }
        draw(0.95F, 0.95F, 0.15F);

        /* Nicht-leere Sections (cyan). */
        if (mode >= 2) {
            this.count = 0;
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    Chunk c = chunks.getChunk(centerCX + dx, centerCZ + dz);
                    if (c == null) continue;
                    int ox = (centerCX + dx) << ChunkSection.SHIFT;
                    int oz = (centerCZ + dz) << ChunkSection.SHIFT;
                    for (int i = 0; i < Chunk.SECTIONS; i++) {
                        ChunkSection s = c.getSection(i);
                        if (s == null || s.isEmpty()) continue;
                        int sy = i << ChunkSection.SHIFT;
                        box(cam, ox, sy, oz, ox + size, sy + size, oz + size);
                    }
                }
            }
            if (this.count > 0) draw(0.2F, 0.9F, 0.9F);
        }

        GL11.glDepthFunc(properties.baseDepthFunc());
        GL11.glDisable(GL11.GL_BLEND);
        this.shader.unbind();
    }

    /** Einzelne Debug-AABB, z.B. Structure-Auswahl. Max-Koordinaten sind inklusiv. */
    public void renderBox(Camera camera, StructureBounds bounds, float r, float g, float b) {
        if (bounds == null) return;
        Vector3d cam = camera.getPosition();
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformVector2f("u_Viewport", SkyEngine.get().getWindow().getWidth(),
                SkyEngine.get().getWindow().getHeight());
        this.shader.setUniformf("u_LineWidth", 2.5F);
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL11.glEnable(GL11.GL_BLEND);
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.orEqualDepthFunc());
        this.count = 0;
        box(cam, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() + 1,
                bounds.maxY() + 1, bounds.maxZ() + 1);
        draw(r, g, b);
        GL11.glDepthFunc(properties.baseDepthFunc());
        GL11.glDisable(GL11.GL_BLEND);
        this.shader.unbind();
    }

    private void draw(float r, float g, float b) {
        if (this.count == 0) return;
        this.shader.setUniformVector3f("u_Color", r, g, b);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, Arrays.copyOf(this.buf, this.count), GL15.GL_DYNAMIC_DRAW);
        GL11.glDrawArrays(GL11.GL_LINES, 0, this.count / 3);
    }

    /** 12 Kanten einer AABB (Weltkoordinaten) kamerarelativ in den Puffer. */
    private void box(Vector3d cam, int x0, int y0, int z0, int x1, int y1, int z1) {
        float ax = (float) (x0 - cam.x), ay = (float) (y0 - cam.y), az = (float) (z0 - cam.z);
        float bx = (float) (x1 - cam.x), by = (float) (y1 - cam.y), bz = (float) (z1 - cam.z);
        /* untere Kanten */
        line(ax, ay, az, bx, ay, az);
        line(bx, ay, az, bx, ay, bz);
        line(bx, ay, bz, ax, ay, bz);
        line(ax, ay, bz, ax, ay, az);
        /* obere Kanten */
        line(ax, by, az, bx, by, az);
        line(bx, by, az, bx, by, bz);
        line(bx, by, bz, ax, by, bz);
        line(ax, by, bz, ax, by, az);
        /* Vertikalen */
        line(ax, ay, az, ax, by, az);
        line(bx, ay, az, bx, by, az);
        line(bx, ay, bz, bx, by, bz);
        line(ax, ay, bz, ax, by, bz);
    }

    private void line(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (this.count + 6 > this.buf.length) {
            this.buf = Arrays.copyOf(this.buf, this.buf.length * 2);
        }
        this.buf[this.count++] = x0;
        this.buf[this.count++] = y0;
        this.buf[this.count++] = z0;
        this.buf[this.count++] = x1;
        this.buf[this.count++] = y1;
        this.buf[this.count++] = z1;
    }

    public void dispose() {
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        this.shader.dispose();
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        uniform mat4 u_ProjectionView;
        void main() {
            /* Positionen sind bereits kamerarelativ (CPU-gebacken); winziger Tiefen-Bias Richtung
               Kamera wie MCs VIEW_OFFSET_Z_LAYERING, damit koplanare Kanten sichtbar bleiben. */
            gl_Position = u_ProjectionView * vec4(a_position * 0.99975586, 1.0);
        }
        """;

    private static final String GEOMETRY = """
        #version 460 core
        layout(lines) in;
        layout(triangle_strip, max_vertices = 4) out;

        uniform vec2 u_Viewport;
        uniform float u_LineWidth;

        void main() {
            vec4 p0 = gl_in[0].gl_Position;
            vec4 p1 = gl_in[1].gl_Position;

            vec2 ndc0 = p0.xy / p0.w;
            vec2 ndc1 = p1.xy / p1.w;

            vec2 dir = normalize((ndc1 - ndc0) * u_Viewport);
            vec2 normal = vec2(-dir.y, dir.x);
            vec2 offset = normal * u_LineWidth / u_Viewport;

            gl_Position = vec4((ndc0 + offset) * p0.w, p0.z, p0.w); EmitVertex();
            gl_Position = vec4((ndc0 - offset) * p0.w, p0.z, p0.w); EmitVertex();
            gl_Position = vec4((ndc1 + offset) * p1.w, p1.z, p1.w); EmitVertex();
            gl_Position = vec4((ndc1 - offset) * p1.w, p1.z, p1.w); EmitVertex();
            EndPrimitive();
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        uniform vec3 u_Color;
        out vec4 fragColor;
        void main() {
            fragColor = vec4(u_Color, 0.75);
        }
        """;
}
