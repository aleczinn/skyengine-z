package de.skyengine.graphics.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.structure.StructureEditorSession;
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

import java.util.Arrays;

/** Texturierte, rein visuelle Ghost-Darstellung eines Structure-Clipboards. */
public final class StructurePreviewRenderer {
    private static final int STRIDE = 9;
    private ShaderProgram shader;
    private TextureArray textures;
    private int vao, vbo, vertices;
    private StructureEditorSession.Preview cached;

    public void init(TextureArray textures) {
        this.textures = textures;
        this.shader = new ShaderProgram(new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.unbind();
        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE * 4, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, STRIDE * 4, 3L * 4);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, STRIDE * 4, 6L * 4);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glEnableVertexAttribArray(2);
        GL30.glBindVertexArray(0);
        GlDebug.labelBuffer(this.vbo, "Structure Preview VBO");
    }

    public void invalidate() { this.cached = null; }

    public void render(Camera camera, StructureEditorSession.Preview preview) {
        if (preview == null) return;
        if (!preview.equals(this.cached)) rebuild(preview);
        if (vertices == 0) return;
        Vector3d cam = camera.getPosition();
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformVector3f("u_Offset", (float) (preview.x() - cam.x),
                (float) (preview.y() - cam.y), (float) (preview.z() - cam.z));
        this.shader.setUniformf("u_Alpha", 0.38F);
        this.textures.bind(0);
        GL30.glBindVertexArray(this.vao);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.vertices);
        GL11.glDepthMask(true);
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL30.glBindVertexArray(0);
        this.shader.unbind();
    }

    private void rebuild(StructureEditorSession.Preview preview) {
        FloatBuilder out = new FloatBuilder(Math.max(1024,
                Math.min(preview.template().cells().size() * 6 * STRIDE, 1_000_000)));
        for (var cell : preview.template().cells()) {
            if (cell.state() == Blocks.AIR) continue;
            var state = preview.transform().state(Blocks.getState(cell.state()));
            BakedQuad[] quads = state.getModel();
            if (quads == null) continue;
            int relX = cell.x() - preview.template().anchorX();
            int relZ = cell.z() - preview.template().anchorZ();
            float ox = preview.transform().transformedX(relX, relZ);
            float oy = cell.y() - preview.template().anchorY();
            float oz = preview.transform().transformedZ(relX, relZ);
            for (BakedQuad quad : quads) append(out, quad, ox, oy, oz);
            for (BakedQuad quad : state.getOverlay()) append(out, quad, ox, oy, oz);
        }
        float[] data = out.toArray();
        this.vertices = data.length / STRIDE;
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindVertexArray(0);
        this.cached = preview;
    }

    private static void append(FloatBuilder out, BakedQuad quad, float ox, float oy, float oz) {
        float[] vertices = quad.vertices();
        int tint = quad.tint();
        float r = quad.brightness() * ((tint >> 16) & 255) / 255F;
        float g = quad.brightness() * ((tint >> 8) & 255) / 255F;
        float b = quad.brightness() * (tint & 255) / 255F;
        for (int i = 0; i < vertices.length; i += 5) {
            out.add(vertices[i] + ox); out.add(vertices[i + 1] + oy); out.add(vertices[i + 2] + oz);
            out.add(vertices[i + 3]); out.add(vertices[i + 4]); out.add(quad.textureLayer());
            out.add(r); out.add(g); out.add(b);
        }
    }

    public void dispose() {
        if (this.vao != 0) GL30.glDeleteVertexArrays(this.vao);
        if (this.vbo != 0) GL15.glDeleteBuffers(this.vbo);
        if (this.shader != null) this.shader.dispose();
    }

    private static final class FloatBuilder {
        private float[] data;
        private int size;
        FloatBuilder(int capacity) { this.data = new float[capacity]; }
        void add(float value) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = value;
        }
        float[] toArray() { return Arrays.copyOf(data, size); }
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location=0) in vec3 a_Position;
        layout(location=1) in vec3 a_TexCoord;
        layout(location=2) in vec3 a_Color;
        uniform mat4 u_ProjectionView;
        uniform vec3 u_Offset;
        out vec3 v_TexCoord;
        out vec3 v_Color;
        void main() {
            gl_Position = u_ProjectionView * vec4(a_Position + u_Offset, 1.0);
            v_TexCoord = a_TexCoord;
            v_Color = a_Color;
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_TexCoord;
        in vec3 v_Color;
        uniform sampler2DArray u_Textures;
        uniform float u_Alpha;
        out vec4 fragColor;
        void main() {
            vec4 tex = texture(u_Textures, v_TexCoord);
            if (tex.a < 0.01) discard;
            fragColor = vec4(tex.rgb * v_Color, tex.a * u_Alpha);
        }
        """;
}
