package de.skyengine.graphics.world;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.ChunkMesher;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class SectionMesh {

    public final int chunkX, sectionY, chunkZ;

    /* Index = RenderLayer.ordinal(), null wenn der Layer leer ist */
    private final LayerMesh[] layers = new LayerMesh[RenderLayer.VALUES.length];

    public SectionMesh(int chunkX, int sectionY, int chunkZ, ChunkMesher.MeshData data) {
        this.chunkX = chunkX;
        this.sectionY = sectionY;
        this.chunkZ = chunkZ;

        if (data.opaque != null) this.layers[RenderLayer.OPAQUE.ordinal()] = new LayerMesh(data.opaque);
        if (data.cutout != null) this.layers[RenderLayer.CUTOUT.ordinal()] = new LayerMesh(data.cutout);
        if (data.translucent != null) this.layers[RenderLayer.TRANSLUCENT.ordinal()] = new LayerMesh(data.translucent);
    }

    public boolean hasLayer(RenderLayer layer) {
        return this.layers[layer.ordinal()] != null;
    }

    public void render(RenderLayer layer) {
        LayerMesh mesh = this.layers[layer.ordinal()];
        if (mesh != null) mesh.render();
    }

    public void dispose() {
        for (LayerMesh mesh : this.layers) {
            if (mesh != null) mesh.dispose();
        }
    }

    /* ------------------------------------------------------------------ */

    private static final class LayerMesh {
        private final int vao, vbo;
        private final int vertexCount;

        LayerMesh(float[] data) {
            this.vertexCount = data.length / ChunkMesher.VERTEX_SIZE;

            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();

            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);

            int stride = ChunkMesher.VERTEX_SIZE * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);                 // position
            GL20.glVertexAttribPointer(1, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES);   // uv + layer
            GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES);   // color (helligkeit * tint)
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);

            GL30.glBindVertexArray(0);
        }

        void render() {
            GL30.glBindVertexArray(this.vao);
            GL15.glDrawArrays(GL15.GL_TRIANGLES, 0, this.vertexCount);
        }

        void dispose() {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.vbo);
        }
    }
}