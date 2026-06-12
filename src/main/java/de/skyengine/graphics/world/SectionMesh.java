package de.skyengine.graphics.world;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class SectionMesh {

    public final int chunkX, sectionY, chunkZ;
    private final int vao, vbo;
    private final int vertexCount;

    public SectionMesh(int chunkX, int sectionY, int chunkZ, float[] data) {
        this.chunkX = chunkX;
        this.sectionY = sectionY;
        this.chunkZ = chunkZ;
        this.vertexCount = data.length / 7;

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);

        int stride = 7 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);            // position
        GL20.glVertexAttribPointer(1, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES); // uv + layer
        GL20.glVertexAttribPointer(2, 1, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES); // brightness
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glEnableVertexAttribArray(2);

        GL30.glBindVertexArray(0);
    }

    public void render() {
        GL30.glBindVertexArray(this.vao);
        GL15.glDrawArrays(GL15.GL_TRIANGLES, 0, this.vertexCount);
    }

    public void dispose() {
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
    }
}