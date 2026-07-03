package de.skyengine.graphics.world;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;

public class SectionMesh {

    public final int chunkX, sectionY, chunkZ;

    /* Index = RenderLayer.ordinal(), null wenn der Layer leer ist */
    private final LayerMesh[] layers = new LayerMesh[RenderLayer.VALUES.length];

    /** Ints pro Quad: 4 Vertices à VERTEX_SIZE (gepacktes Format, siehe ChunkMesher). */
    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    /* CPU-Kopie des Translucent-Layers für die Per-Quad-Sortierung (null ohne Translucent-Inhalt).
       Referenz auf das Mesher-Array — wird nach dem Upload sonst nirgends mehr benutzt. */
    private final int[] translucentData;

    /* Kameraposition (Welt) des letzten Quad-Sorts. NaN = noch nie sortiert. */
    private double lastSortX = Double.NaN, lastSortY, lastSortZ;

    /* Wiederverwendete Sortier-Puffer — nur Render-Thread (sortTranslucent läuft nie parallel). */
    private static long[] sortKeys = new long[1024];
    private static int[] sortScratch = new int[1024 * QUAD_INTS];

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

    /** Entpackt eine Fixed-Point-Positions-Komponente (u16, 8.8, Bias +1). */
    private static float unpackPos(int fixed) {
        return fixed / ChunkMesher.POS_SCALE - 1F;
    }

    /**
     * Sortiert die Translucent-Quads back-to-front zur Kamera und lädt den VBO neu hoch
     * (Vanilla-Stil, gegen falsche Blend-Reihenfolge innerhalb einer Section). Sortiert nur,
     * wenn die Kamera seit dem letzten Sort mehr als 1 Block bewegt wurde. Render-Thread only.
     *
     * @return true, wenn tatsächlich neu sortiert wurde (fürs Frame-Budget im ChunkRenderer)
     */
    public boolean sortTranslucent(Vector3d cam) {
        int[] data = this.translucentData;
        if (data == null) return false;

        double mx = cam.x - this.lastSortX, my = cam.y - this.lastSortY, mz = cam.z - this.lastSortZ;
        /* NaN beim ersten Aufruf: Vergleich ist false -> es wird sortiert. */
        if (mx * mx + my * my + mz * mz < 1.0) return false;
        this.lastSortX = cam.x;
        this.lastSortY = cam.y;
        this.lastSortZ = cam.z;

        /* Kamera in Mesh-Koordinaten (section-lokal x/y/z). Ursprung erst in double abziehen,
           dann nach float — innerhalb der Render-Distanz präzise genug. */
        float relX = (float) (cam.x - ((long) this.chunkX << ChunkSection.SHIFT));
        float relY = (float) (cam.y - ((long) this.sectionY << ChunkSection.SHIFT));
        float relZ = (float) (cam.z - ((long) this.chunkZ << ChunkSection.SHIFT));

        int quads = data.length / QUAD_INTS;
        if (sortKeys.length < quads) sortKeys = new long[quads];
        if (sortScratch.length < data.length) sortScratch = new int[data.length];

        for (int q = 0; q < quads; q++) {
            int b = q * QUAD_INTS;
            /* Quad-Mittelpunkt aus den 4 gepackten Ecken (Vertex-Stride = VERTEX_SIZE Ints). */
            float sx = 0F, sy = 0F, sz = 0F;
            for (int v = 0; v < 4; v++) {
                int vb = b + v * ChunkMesher.VERTEX_SIZE;
                sx += unpackPos(data[vb] & 0xFFFF);
                sy += unpackPos(data[vb] >>> 16);
                sz += unpackPos(data[vb + 1] & 0xFFFF);
            }
            float dx = sx * 0.25F - relX;
            float dy = sy * 0.25F - relY;
            float dz = sz * 0.25F - relZ;
            float distSq = dx * dx + dy * dy + dz * dz;
            /* Float-Bits sind für nicht-negative Werte monoton -> direkt als Sortierschlüssel. */
            sortKeys[q] = ((long) Float.floatToIntBits(distSq) << 32) | q;
        }
        Arrays.sort(sortKeys, 0, quads);

        /* Fern -> nah in den Scratch, zurückkopieren (data behält exakte Länge für
           glBufferSubData) und den VBO aktualisieren. */
        int out = 0;
        for (int i = quads - 1; i >= 0; i--) {
            System.arraycopy(data, (int) sortKeys[i] * QUAD_INTS, sortScratch, out, QUAD_INTS);
            out += QUAD_INTS;
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

    /**
     * Geteilter Quad-Index-Buffer (0,1,2, 2,3,0 je Quad) für ALLE Section-Meshes.
     * Wächst bei Bedarf; der alte Buffer wird gelöscht — GL hält ihn am Leben, solange
     * ihn noch ein bestehendes VAO referenziert (bereits gebaute Meshes bleiben gültig).
     * Nur Render-Thread.
     */
    private static int sharedIndexBuffer = 0;
    private static int sharedIndexCapacity = 0; // in Quads

    private static int indexBufferFor(int quads) {
        if (quads > sharedIndexCapacity || sharedIndexBuffer == 0) {
            int newCapacity = Math.max(32768, Integer.highestOneBit(quads - 1) << 1);
            int[] indices = new int[newCapacity * 6];
            for (int q = 0, i = 0; q < newCapacity; q++) {
                int v = q * 4;
                indices[i++] = v;
                indices[i++] = v + 1;
                indices[i++] = v + 2;
                indices[i++] = v + 2;
                indices[i++] = v + 3;
                indices[i++] = v;
            }
            if (sharedIndexBuffer != 0) GL15.glDeleteBuffers(sharedIndexBuffer);
            sharedIndexBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, sharedIndexBuffer);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            sharedIndexCapacity = newCapacity;
        }
        return sharedIndexBuffer;
    }

    private static final class LayerMesh {
        private final int vao, vbo;
        private final int indexCount;

        LayerMesh(int[] data, int usage) {
            int quads = data.length / QUAD_INTS;
            this.indexCount = quads * 6;

            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();

            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, usage);

            /* Ein einziges uvec4-Attribut, entpackt im Vertex-Shader */
            int stride = ChunkMesher.VERTEX_SIZE * Integer.BYTES;
            GL30.glVertexAttribIPointer(0, 4, GL11.GL_UNSIGNED_INT, stride, 0);
            GL20.glEnableVertexAttribArray(0);

            /* EBO-Bindung wird im VAO gespeichert */
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBufferFor(quads));

            GL30.glBindVertexArray(0);
        }

        void render() {
            GL30.glBindVertexArray(this.vao);
            GL11.glDrawElements(GL11.GL_TRIANGLES, this.indexCount, GL11.GL_UNSIGNED_INT, 0L);
        }

        /** Ersetzt den VBO-Inhalt (gleiche Größe wie beim Erzeugen). Render-Thread. */
        void updateData(int[] data) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, data);
        }

        void dispose() {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.vbo);
        }
    }
}
