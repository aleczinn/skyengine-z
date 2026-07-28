package de.skyengine.graphics.world;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import org.joml.Vector3d;

import java.util.Arrays;

/**
 * Section-Mesh als Mieter der {@link VertexArena}: hält pro RenderLayer nur noch eine
 * Arena-Region + Quad-Anzahl — keine eigenen VAOs/VBOs. Gezeichnet wird zentral im
 * {@link ChunkRenderer} über MultiDrawIndirect (baseVertex = Region-Offset).
 */
public class SectionMesh {

    public final int chunkX, sectionY, chunkZ;

    /* Descriptor-Slots im GPU-Cull-Substrat (-1 = nicht registriert), gepflegt vom ChunkRenderer. */
    int gpuSlotOpaque = -1, gpuSlotCutout = -1;

    /** Ints pro Quad: 4 Vertices à VERTEX_SIZE (gepacktes Format, siehe ChunkMesher). */
    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    /* Index = RenderLayer.ordinal(); Region null = Layer leer */
    private final VertexArena.Region[] regions = new VertexArena.Region[RenderLayer.VALUES.length];
    private final int[] quadCounts = new int[RenderLayer.VALUES.length];

    /* Kleinvegetations-Segment (Gras/Blumen/Pilze): eigene Region in der CUTOUT-Arena,
       eigenes Draw-Segment im Renderer (distanzabhängige Ausdünnung/Skip). */
    private VertexArena.Region detailRegion;
    private int detailQuadCount;

    /* CPU-Kopie des Translucent-Layers für die Per-Quad-Sortierung (null ohne Translucent-Inhalt).
       Referenz auf das Mesher-Array — wird nach dem Upload sonst nirgends mehr benutzt. */
    private final int[] translucentData;

    /* Kameraposition (Welt) des letzten Quad-Sorts. NaN = noch nie sortiert. */
    private double lastSortX = Double.NaN, lastSortY, lastSortZ;

    /* Wiederverwendete Sortier-Puffer — nur Render-Thread (sortTranslucent läuft nie parallel). */
    private static long[] sortKeys = new long[1024];
    private static int[] sortScratch = new int[1024 * QUAD_INTS];

    /** Alloziert die Regionen aller nicht-leeren Layer. Render-Thread. */
    public SectionMesh(int chunkX, int sectionY, int chunkZ, ChunkMesher.MeshData data, VertexArena[] arenas) {
        this.chunkX = chunkX;
        this.sectionY = sectionY;
        this.chunkZ = chunkZ;
        this.translucentData = data.translucent;

        this.upload(RenderLayer.OPAQUE, data.opaque, arenas);
        this.upload(RenderLayer.CUTOUT, data.cutout, arenas);
        this.upload(RenderLayer.TRANSLUCENT, data.translucent, arenas);
        if (data.detail != null) {
            this.detailRegion = arenas[RenderLayer.CUTOUT.ordinal()].alloc(data.detail);
            this.detailQuadCount = data.detail.length / QUAD_INTS;
        }
    }

    private void upload(RenderLayer layer, int[] data, VertexArena[] arenas) {
        if (data == null) return;
        int i = layer.ordinal();
        this.regions[i] = arenas[i].alloc(data);
        this.quadCounts[i] = data.length / QUAD_INTS;
    }

    public boolean hasLayer(RenderLayer layer) {
        return this.regions[layer.ordinal()] != null;
    }

    /** baseVertex für den Indirect-Command dieses Layers. Nur bei hasLayer aufrufen. */
    public int baseVertex(RenderLayer layer) {
        return this.regions[layer.ordinal()].vertexOffset();
    }

    /** Index-Anzahl für den Indirect-Command dieses Layers (Quads · 6). */
    public int indexCount(RenderLayer layer) {
        return this.quadCounts[layer.ordinal()] * 6;
    }

    public boolean hasDetail() {
        return this.detailRegion != null;
    }

    /** baseVertex des Kleinvegetations-Segments (CUTOUT-Arena). Nur bei hasDetail aufrufen. */
    public int baseVertexDetail() {
        return this.detailRegion.vertexOffset();
    }

    /** Index-Anzahl des Kleinvegetations-Segments (Quads · 6). */
    public int indexCountDetail() {
        return this.detailQuadCount * 6;
    }

    /** Größte Quad-Anzahl aller Layer — fürs Sizing des geteilten Index-Buffers. */
    public int maxQuads() {
        return Math.max(Math.max(this.quadCounts[0], this.detailQuadCount),
                Math.max(this.quadCounts[1], this.quadCounts[2]));
    }

    /** Entpackt eine Fixed-Point-Positions-Komponente (u16, 6.10, Bias +1). */
    private static float unpackPos(int fixed) {
        return fixed / ChunkMesher.POS_SCALE - 1F;
    }

    /**
     * Sortiert die Translucent-Quads back-to-front zur Kamera (Vanilla-Stil, gegen falsche
     * Blend-Reihenfolge innerhalb einer Section). Die sortierten Daten wandern in eine NEUE
     * Arena-Region (alte wird deferred freigegeben) — in-place schreiben wäre ein Sync-Hazard,
     * weil die GPU die alte Region noch aus Vorframes lesen kann. Sortiert nur, wenn die
     * Kamera seit dem letzten Sort mehr als 1 Block bewegt wurde. Render-Thread only.
     *
     * @return true, wenn tatsächlich neu sortiert wurde (fürs Frame-Budget im ChunkRenderer)
     */
    public boolean sortTranslucent(Vector3d cam, VertexArena arena, long currentFrame) {
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

        /* Fern -> nah in den Scratch, zurückkopieren und in eine frische Region hochladen. */
        int out = 0;
        for (int i = quads - 1; i >= 0; i--) {
            System.arraycopy(data, (int) sortKeys[i] * QUAD_INTS, sortScratch, out, QUAD_INTS);
            out += QUAD_INTS;
        }
        System.arraycopy(sortScratch, 0, data, 0, data.length);

        int layer = RenderLayer.TRANSLUCENT.ordinal();
        arena.free(this.regions[layer], currentFrame);
        this.regions[layer] = arena.alloc(data);
        return true;
    }

    /** Gibt alle Regionen deferred frei. */
    public void dispose(VertexArena[] arenas, long currentFrame) {
        for (int i = 0; i < this.regions.length; i++) {
            if (this.regions[i] != null) {
                arenas[i].free(this.regions[i], currentFrame);
                this.regions[i] = null;
            }
        }
        if (this.detailRegion != null) {
            arenas[RenderLayer.CUTOUT.ordinal()].free(this.detailRegion, currentFrame);
            this.detailRegion = null;
        }
    }
}
