package de.skyengine.graphics.world;

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

    private ShaderProgram shader;
    private int vao, vbo;

    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT)
        );

        /* 12 Kanten eines Einheitswürfels, leicht aufgeblasen gegen Z-Fighting */
        float min = -0.002F, max = 1.002F;
        float[] lines = {
                min,min,min, max,min,min,  max,min,min, max,min,max,  max,min,max, min,min,max,  min,min,max, min,min,min, // unten
                min,max,min, max,max,min,  max,max,min, max,max,max,  max,max,max, min,max,max,  min,max,max, min,max,min, // oben
                min,min,min, min,max,min,  max,min,min, max,max,min,  max,min,max, max,max,max,  min,min,max, min,max,max  // vertikal
        };

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, lines, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    /** blockX/Y/Z aus dem Raycast-Hit, camera-relativ wie die Chunks */
    public void render(Camera camera, int blockX, int blockY, int blockZ) {
        Vector3d cam = camera.getPosition();

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformVector3f("u_Offset",
                (float) (blockX - cam.x), (float) (blockY - cam.y), (float) (blockZ - cam.z));

        GL30.glBindVertexArray(this.vao);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDrawArrays(GL11.GL_LINES, 0, 24);
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
                gl_Position = u_ProjectionView * vec4(a_position + u_Offset, 1.0);
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
