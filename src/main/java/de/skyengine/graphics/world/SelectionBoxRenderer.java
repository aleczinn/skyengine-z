package de.skyengine.graphics.world;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeOutline;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class SelectionBoxRenderer {

    private static final int INITIAL_FLOATS = 24 * 3; // Startgröße des VBO (wächst bei Bedarf)

    private ShaderProgram shader;
    private int vao, vbo;

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
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, INITIAL_FLOATS * 4L, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    /** blockX/Y/Z aus dem Raycast-Hit, camera-relativ wie die Chunks. */
    public void render(Camera camera, int blockX, int blockY, int blockZ, BlockShape outline) {
        AABB[] localBoxes = outline.isEmpty() ? BlockShape.FULL_CUBE.boxes() : outline.boxes();

        /* Zusammengefasste Silhouette der Vereinigung (eine Kontur statt Box-für-Box).
           Kein Welt-Inflate: die Kanten liegen exakt auf den Blockgrenzen — der Tiefen-Bias
           (glPolygonOffset, s.u.) sorgt für die Sichtbarkeit gegen koplanare Flächen. */
        float[] edges = ShapeOutline.build(localBoxes, 0f);
        if (edges.length == 0) return;

        Vector3d cam = camera.getPosition();

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformVector3f("u_Offset",
                (float) (blockX - cam.x), (float) (blockY - cam.y), (float) (blockZ - cam.z));
        this.shader.setUniformVector2f("u_Viewport",
                SkyEngine.get().getWindow().getWidth(),
                SkyEngine.get().getWindow().getHeight()
        );
        this.shader.setUniformf("u_LineWidth", 2.5F); // Pixel

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL11.glEnable(GL11.GL_BLEND);

        /* Sichtbarkeit wie die Selection-Box in Minecraft: koplanare Kanten (auf eigenen Block-Faces
           + Nachbarn wie dem Grasblock darunter) sollen den Tiefentest gewinnen. Dazu die „or-equal"-
           Variante der AKTIVEN Depth-Func nehmen — die Engine läuft im Reversed-Z-Modus (GL_GREATER,
           nah≈1), wo GL_LEQUAL die ferneren Rückkanten gewinnen ließe (= Durchscheinen). Den winzigen
           Tiefen-Bias liefert der Vertex-Shader (Skalierung Richtung Kamera, wie MCs
           VIEW_OFFSET_Z_LAYERING). Tiefentest bleibt aktiv -> echt verdeckte Kanten bleiben verborgen. */
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int orEqualFunc = switch (prevDepthFunc) {
            case GL11.GL_GREATER -> GL11.GL_GEQUAL; // Reversed-Z
            case GL11.GL_LESS -> GL11.GL_LEQUAL;    // Standard-Z
            default -> prevDepthFunc;               // bereits inklusiv / Sonderfall
        };
        GL11.glDepthFunc(orEqualFunc);

        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, edges, GL15.GL_DYNAMIC_DRAW);
        GL11.glDrawArrays(GL11.GL_LINES, 0, edges.length / 3);

        GL11.glDepthFunc(prevDepthFunc);
        GL11.glDisable(GL11.GL_BLEND);
        this.shader.unbind();
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
        uniform vec3 u_Offset;
        void main() {
            /* Wie Minecrafts VIEW_OFFSET_Z_LAYERING: die kamerarelative Position minimal Richtung
               Kamera skalieren (Faktor 4095/4096). Distanzproportionaler, steigungsunabhängiger
               Tiefen-Bias — macht koplanare Kanten sichtbar, lässt aber Kanten hinter massiven
               Blöcken NICHT durchscheinen. */
            vec3 camRel = (a_position + u_Offset) * 0.99975586;
            gl_Position = u_ProjectionView * vec4(camRel, 1.0);
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

            /* Endpunkte in NDC (Normalized Device Coordinates) */
            vec2 ndc0 = p0.xy / p0.w;
            vec2 ndc1 = p1.xy / p1.w;

            /* Linienrichtung in Pixeln, daraus die Normale */
            vec2 dir = normalize((ndc1 - ndc0) * u_Viewport);
            vec2 normal = vec2(-dir.y, dir.x);

            /* Halbe Breite pro Seite: 1px entspricht 2.0/Viewport in NDC */
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
        out vec4 fragColor;
        void main() {
            fragColor = vec4(0.0, 0.0, 0.0, 0.6);
        }
        """;
}
