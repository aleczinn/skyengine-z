package de.skyengine.graphics.world;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;

/** Gemeinsame, homogen geclippte Pipeline fuer 3D-Debug-Linien. */
public final class DebugLineRenderer {

    private ShaderProgram shader;
    private int vao, vbo;

    public void init(String label) {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(GEOMETRY, ShaderType.GEOMETRY),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 24L * 3L * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
        GlDebug.labelBuffer(this.vbo, label);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    public void render(Camera camera, float[] vertices, int count, float width,
                       float r, float g, float b, float a) {
        render(camera, vertices, count, width, r, g, b, a, 0);
    }

    /**
     * Zeichnet Linien nach dem Post-Processing, verwirft aber Fragmente hinter der aufgeloesten
     * Szenentiefe. Der Default-Framebuffer besitzt an dieser Stelle keine Welt-Tiefe; deshalb
     * erfolgt der Vergleich gezielt im Fragmentshader statt ueber globalen GL-Depth-State.
     */
    public void renderDepthOccluded(Camera camera, float[] vertices, int count, float width,
                                    float r, float g, float b, float a, int sceneDepthTexture) {
        render(camera, vertices, count, width, r, g, b, a, sceneDepthTexture);
    }

    private void render(Camera camera, float[] vertices, int count, float width,
                        float r, float g, float b, float a, int sceneDepthTexture) {
        if (count == 0) return;
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getUnjitteredProjectionViewMatrix());
        this.shader.setUniformVector2f("u_Viewport", SkyEngine.get().getWindow().getWidth(),
                SkyEngine.get().getWindow().getHeight());
        this.shader.setUniformf("u_LineWidth", width);
        this.shader.setUniformVector4f("u_Color", r, g, b, a);
        boolean reversedDepth = SkyEngine.get().getWindow().getProperties().isUseInverseDepth();
        this.shader.setUniformi("u_ZeroToOne", reversedDepth ? 1 : 0);
        this.shader.setUniformi("u_DepthOcclusion", sceneDepthTexture != 0 ? 1 : 0);
        this.shader.setUniformi("u_ReversedDepth", reversedDepth ? 1 : 0);
        this.shader.setUniformi("u_SceneDepth", 0);
        GL11.glViewport(0, 0, SkyEngine.get().getWindow().getWidth(),
                SkyEngine.get().getWindow().getHeight());

        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthWriteWasEnabled = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);

        if (sceneDepthTexture != 0) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepthTexture);
        }

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, Arrays.copyOf(vertices, count), GL15.GL_DYNAMIC_DRAW);
        GL11.glDrawArrays(GL11.GL_LINES, 0, count / 3);
        GL30.glBindVertexArray(0);

        if (sceneDepthTexture != 0) GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL11.glDepthMask(depthWriteWasEnabled);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (cullWasEnabled) GL11.glEnable(GL11.GL_CULL_FACE);
        if (!blendWasEnabled) GL11.glDisable(GL11.GL_BLEND);
        this.shader.unbind();
    }

    public void dispose() {
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        if (this.shader != null) this.shader.dispose();
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        uniform mat4 u_ProjectionView;
        void main() { gl_Position = u_ProjectionView * vec4(a_position, 1.0); }
        """;

    private static final String GEOMETRY = """
        #version 460 core
        layout(lines) in;
        layout(triangle_strip, max_vertices = 4) out;
        uniform vec2 u_Viewport;
        uniform float u_LineWidth;
        uniform int u_ZeroToOne;

        bool clipPlane(float f0, float f1, inout float first, inout float last) {
            if (f0 < 0.0 && f1 < 0.0) return false;
            if ((f0 < 0.0) != (f1 < 0.0)) {
                float at = f0 / (f0 - f1);
                if (f0 < 0.0) first = max(first, at);
                else last = min(last, at);
            }
            return first <= last;
        }

        void main() {
            vec4 original0 = gl_in[0].gl_Position;
            vec4 original1 = gl_in[1].gl_Position;
            float first = 0.0;
            float last = 1.0;
            if (!clipPlane(original0.w - 0.0001, original1.w - 0.0001, first, last)) return;
            if (!clipPlane(original0.x + original0.w, original1.x + original1.w, first, last)) return;
            if (!clipPlane(original0.w - original0.x, original1.w - original1.x, first, last)) return;
            if (!clipPlane(original0.y + original0.w, original1.y + original1.w, first, last)) return;
            if (!clipPlane(original0.w - original0.y, original1.w - original1.y, first, last)) return;
            if (u_ZeroToOne != 0) {
                if (!clipPlane(original0.z, original1.z, first, last)) return;
            } else {
                if (!clipPlane(original0.z + original0.w, original1.z + original1.w, first, last)) return;
            }
            if (!clipPlane(original0.w - original0.z, original1.w - original1.z, first, last)) return;

            vec4 p0 = mix(original0, original1, first);
            vec4 p1 = mix(original0, original1, last);
            vec2 ndc0 = p0.xy / p0.w;
            vec2 ndc1 = p1.xy / p1.w;
            vec2 delta = (ndc1 - ndc0) * u_Viewport;
            float lengthSquared = dot(delta, delta);
            if (lengthSquared < 0.0001) return;
            vec2 normal = vec2(-delta.y, delta.x) * inversesqrt(lengthSquared);
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
        uniform vec4 u_Color;
        uniform sampler2D u_SceneDepth;
        uniform int u_DepthOcclusion;
        uniform int u_ReversedDepth;
        layout(location = 0) out vec4 fragColor;
        void main() {
            if (u_DepthOcclusion != 0) {
                float sceneDepth = texelFetch(u_SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
                bool hidden = u_ReversedDepth != 0
                        ? gl_FragCoord.z < sceneDepth
                        : gl_FragCoord.z > sceneDepth;
                if (hidden) discard;
            }
            fragColor = u_Color;
        }
        """;
}
