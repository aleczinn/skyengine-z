package de.skyengine.graphics.world;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;

public class SectionMesh {

    public final int chunkX, sectionY, chunkZ;

    /* Index = RenderLayer.ordinal(), null wenn der Layer leer ist */
    private final LayerMesh[] layers = new LayerMesh[RenderLayer.VALUES.length];

    /** Floats pro Quad: 6 Vertices à VERTEX_SIZE. */
    private static final int QUAD_FLOATS = 6 * ChunkMesher.VERTEX_SIZE;

    /* CPU-Kopie des Translucent-Layers für die Per-Quad-Sortierung (null ohne Translucent-Inhalt).
       Referenz auf das Mesher-Array — wird nach dem Upload sonst nirgends mehr benutzt. */
    private final float[] translucentData;

    /* Kameraposition (Welt) des letzten Quad-Sorts. NaN = noch nie sortiert. */
    private double lastSortX = Double.NaN, lastSortY, lastSortZ;

    /* Wiederverwendete Sortier-Puffer — nur Render-Thread (sortTranslucent läuft nie parallel). */
    private static long[] sortKeys = new long[1024];
    private static float[] sortScratch = new float[1024 * QUAD_FLOATS];

    public SectionMesh(int chunkX, int sectionY, int chunkZ, ChunkMesher.MeshData data) {
        this.chunkX = chunkX;
        this.sectionY = sectionY;
        this.chunkZ = chunkZ;
        this.translucentData = data.translucent;

        if (data.opaque != null) this.layers[RenderLayer.OPAQUE.ordinal()] = new LayerMesh(data.opaque, GL15.GL_STATIC_DRAW);
        if (data.cutout != null) this.layers[RenderLayer.CUTOUT.ordinal()] = new LayerMesh(data.cutout, GL15.GL_STATIC_DRAW);
        /* Translucent wird beim Quad-Sort wiederholt neu hochgeladen */
        if (data.translucent != null) this.layers[RenderLayer.TRANSLUCENT.ordinal()] = new LayerMesh(data.translucent, GL15.GL_DYNAMIC_DRAW);
    }

    public boolean hasLayer(RenderLayer layer) {
        return this.layers[layer.ordinal()] != null;
    }

    public void render(RenderLayer layer) {
        LayerMesh mesh = this.layers[layer.ordinal()];
        if (mesh != null) mesh.render();
    }

    /**
     * Sortiert die Translucent-Quads back-to-front zur Kamera und lädt den VBO neu hoch
     * (Vanilla-Stil, gegen falsche Blend-Reihenfolge innerhalb einer Section). Sortiert nur,
     * wenn die Kamera seit dem letzten Sort mehr als 1 Block bewegt wurde. Render-Thread only.
     *
     * @return true, wenn tatsächlich neu sortiert wurde (fürs Frame-Budget im ChunkRenderer)
     */
    public boolean sortTranslucent(Vector3d cam) {
        float[] data = this.translucentData;
        if (data == null) return false;

        double mx = cam.x - this.lastSortX, my = cam.y - this.lastSortY, mz = cam.z - this.lastSortZ;
        /* NaN beim ersten Aufruf: Vergleich ist false -> es wird sortiert. */
        if (mx * mx + my * my + mz * mz < 1.0) return false;
        this.lastSortX = cam.x;
        this.lastSortY = cam.y;
        this.lastSortZ = cam.z;

        /* Kamera in Mesh-Koordinaten: x/z section-lokal, y ist bereits Welt-Y. Chunk-Ursprung
           erst in double abziehen, dann nach float — innerhalb der Render-Distanz präzise genug. */
        float relX = (float) (cam.x - ((long) this.chunkX << ChunkSection.SHIFT));
        float relY = (float) cam.y;
        float relZ = (float) (cam.z - ((long) this.chunkZ << ChunkSection.SHIFT));

        int quads = data.length / QUAD_FLOATS;
        if (sortKeys.length < quads) sortKeys = new long[quads];
        if (sortScratch.length < data.length) sortScratch = new float[data.length];

        for (int q = 0; q < quads; q++) {
            int b = q * QUAD_FLOATS;
            /* Eindeutige Ecken A,B,C,D liegen bei Vertex 0,1,2,4 (Quad = A,B,C,C,D,A). */
            float dx = (data[b] + data[b + 9] + data[b + 18] + data[b + 36]) * 0.25f - relX;
            float dy = (data[b + 1] + data[b + 10] + data[b + 19] + data[b + 37]) * 0.25f - relY;
            float dz = (data[b + 2] + data[b + 11] + data[b + 20] + data[b + 38]) * 0.25f - relZ;
            float distSq = dx * dx + dy * dy + dz * dz;
            /* Float-Bits sind für nicht-negative Werte monoton -> direkt als Sortierschlüssel. */
            sortKeys[q] = ((long) Float.floatToIntBits(distSq) << 32) | q;
        }
        Arrays.sort(sortKeys, 0, quads);

        /* Fern -> nah in den Scratch, zurückkopieren (data behält exakte Länge für
           glBufferSubData) und den VBO aktualisieren. */
        int out = 0;
        for (int i = quads - 1; i >= 0; i--) {
            System.arraycopy(data, (int) sortKeys[i] * QUAD_FLOATS, sortScratch, out, QUAD_FLOATS);
            out += QUAD_FLOATS;
        }
        System.arraycopy(sortScratch, 0, data, 0, data.length);
        this.layers[RenderLayer.TRANSLUCENT.ordinal()].updateData(data);
        return true;
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

        LayerMesh(float[] data, int usage) {
            this.vertexCount = data.length / ChunkMesher.VERTEX_SIZE;

            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();

            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, usage);

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

        /** Ersetzt den VBO-Inhalt (gleiche Größe wie beim Erzeugen). Render-Thread. */
        void updateData(float[] data) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, data);
        }

        void dispose() {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.vbo);
        }
    }
}
