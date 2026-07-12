package de.skyengine.graphics.world;

import de.skyengine.core.EngineProperties;
import de.skyengine.core.SkyEngine;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Abbau-Riss-Overlay (Survival-Mining): zeichnet die destroy_stage-Textur über die
 * Outline-Boxen des Ziel-Blocks — texturierte Quads statt Linien, sonst dasselbe Muster
 * wie {@link SelectionBoxRenderer} (kamerarelativ, Reversed-Z-sicherer Tiefen-Bias im
 * Vertex-Shader, „or-equal"-Depth-Func während des Draws). Nur Render-Thread.
 */
public class CrackRenderer {

    /* 6 Faces je Box, 6 Vertices je Face, 6 Floats je Vertex (x,y,z,u,v,layer) */
    private static final int FLOATS_PER_BOX = 6 * 6 * 6;

    private ShaderProgram shader;
    private int vao, vbo;
    private TextureArray textures;
    private final int[] stageLayers = new int[10];

    /** Nach Blocks.bootstrap aufrufen (destroy_stage-Layer sind dort registriert). */
    public void init(TextureArray textures) {
        this.textures = textures;
        for (int i = 0; i < 10; i++) {
            /* idempotent: die Pfade wurden beim Bootstrap registriert, hier kommen nur die Indizes. */
            this.stageLayers[i] = BlockTextures.layerOf("game/textures/block/destroy_stage_" + i + ".png");
        }

        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT)
        );

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, FLOATS_PER_BOX * 4L, GL15.GL_DYNAMIC_DRAW);
        GlDebug.labelBuffer(this.vbo, "CrackRenderer VBO (Streaming)");
        int stride = 6 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL30.glBindVertexArray(0);
    }

    /** Zeichnet Stage {@code 0..9} über die Outline-Boxen des Blocks (kamerarelativ). */
    public void render(Camera camera, int blockX, int blockY, int blockZ, BlockShape outline, int stage) {
        AABB[] boxes = outline.isEmpty() ? BlockShape.FULL_CUBE.boxes() : outline.boxes();
        if (boxes.length == 0) return;

        float layer = this.stageLayers[Math.max(0, Math.min(9, stage))];
        float[] data = new float[boxes.length * FLOATS_PER_BOX];
        int p = 0;
        for (AABB box : boxes) {
            p = buildBoxFaces(data, p, box, layer);
        }

        Vector3d cam = camera.getPosition();

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformVector3f("u_Offset",
                (float) (blockX - cam.x), (float) (blockY - cam.y), (float) (blockZ - cam.z));
        this.shader.setUniformi("u_Textures", 0);
        this.textures.bind(0);

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL11.glEnable(GL11.GL_BLEND);
        /* Wie MCs "Crumbling": Risse multiplizieren sich in die Blocktextur (2*src*dst),
           statt hell drüberzuliegen. Danach die globale Alpha-Blend-Func wiederherstellen. */
        GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR);

        /* Koplanar zur Block-Oberfläche: „or-equal"-Variante der Basis-Depth-Func
           (Reversed-Z: GEQUAL), Bias liefert der Vertex-Shader (wie SelectionBox).
           Funcs statisch aus EngineProperties statt glGetInteger (synchroner Roundtrip). */
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.orEqualDepthFunc());

        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, data.length / 6);

        GL11.glDepthFunc(properties.baseDepthFunc());
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_BLEND);
        this.shader.unbind();
    }

    /** 6 texturierte Faces einer lokalen Box (CCW von außen, UV 0..1 je Face). */
    private static int buildBoxFaces(float[] d, int p, AABB box, float layer) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        // oben (y+)
        p = vert(d, p, x0, y1, z0, 0, 0, layer); p = vert(d, p, x0, y1, z1, 0, 1, layer); p = vert(d, p, x1, y1, z1, 1, 1, layer);
        p = vert(d, p, x1, y1, z1, 1, 1, layer); p = vert(d, p, x1, y1, z0, 1, 0, layer); p = vert(d, p, x0, y1, z0, 0, 0, layer);
        // unten (y-)
        p = vert(d, p, x0, y0, z0, 0, 0, layer); p = vert(d, p, x1, y0, z0, 1, 0, layer); p = vert(d, p, x1, y0, z1, 1, 1, layer);
        p = vert(d, p, x1, y0, z1, 1, 1, layer); p = vert(d, p, x0, y0, z1, 0, 1, layer); p = vert(d, p, x0, y0, z0, 0, 0, layer);
        // Nord (z-)
        p = vert(d, p, x1, y0, z0, 0, 1, layer); p = vert(d, p, x0, y0, z0, 1, 1, layer); p = vert(d, p, x0, y1, z0, 1, 0, layer);
        p = vert(d, p, x0, y1, z0, 1, 0, layer); p = vert(d, p, x1, y1, z0, 0, 0, layer); p = vert(d, p, x1, y0, z0, 0, 1, layer);
        // Süd (z+)
        p = vert(d, p, x0, y0, z1, 0, 1, layer); p = vert(d, p, x1, y0, z1, 1, 1, layer); p = vert(d, p, x1, y1, z1, 1, 0, layer);
        p = vert(d, p, x1, y1, z1, 1, 0, layer); p = vert(d, p, x0, y1, z1, 0, 0, layer); p = vert(d, p, x0, y0, z1, 0, 1, layer);
        // West (x-)
        p = vert(d, p, x0, y0, z0, 0, 1, layer); p = vert(d, p, x0, y0, z1, 1, 1, layer); p = vert(d, p, x0, y1, z1, 1, 0, layer);
        p = vert(d, p, x0, y1, z1, 1, 0, layer); p = vert(d, p, x0, y1, z0, 0, 0, layer); p = vert(d, p, x0, y0, z0, 0, 1, layer);
        // Ost (x+)
        p = vert(d, p, x1, y0, z1, 0, 1, layer); p = vert(d, p, x1, y0, z0, 1, 1, layer); p = vert(d, p, x1, y1, z0, 1, 0, layer);
        p = vert(d, p, x1, y1, z0, 1, 0, layer); p = vert(d, p, x1, y1, z1, 0, 0, layer); p = vert(d, p, x1, y0, z1, 0, 1, layer);
        return p;
    }

    private static int vert(float[] d, int p, float x, float y, float z, float u, float v, float layer) {
        d[p++] = x; d[p++] = y; d[p++] = z; d[p++] = u; d[p++] = v; d[p++] = layer;
        return p;
    }

    public void dispose() {
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        if (this.shader != null) this.shader.dispose();
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_texCoord;
        uniform mat4 u_ProjectionView;
        uniform vec3 u_Offset;
        out vec3 v_texCoord;
        void main() {
            v_texCoord = a_texCoord;
            /* Wie SelectionBox/MCs VIEW_OFFSET_Z_LAYERING: kamerarelativ minimal zur Kamera
               skalieren (4095/4096) — abstandsproportionaler, Reversed-Z-sicherer Tiefen-Bias. */
            vec3 camRel = (a_position + u_Offset) * 0.99975586;
            gl_Position = u_ProjectionView * vec4(camRel, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_texCoord;
        uniform sampler2DArray u_Textures;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < 0.1) discard;
            fragColor = c;
        }
        """;
}
