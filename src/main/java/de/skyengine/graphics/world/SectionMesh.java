package de.skyengine.graphics.world;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.PackedTerrainQuad;
import de.skyengine.graphics.camera.Camera;
import org.joml.Vector3d;

import java.util.Arrays;

/**
 * Section-Mesh als Mieter der {@link VertexArena}: hält pro RenderLayer nur noch eine
 * Arena-Region + Quad-Anzahl — keine eigenen VAOs/VBOs. Gezeichnet wird zentral im
 * {@link ChunkRenderer} über MultiDrawIndirect (baseVertex = Region-Offset).
 */
public class SectionMesh {

    public final int chunkX, sectionY, chunkZ;

    /** Ints pro Quad: 4 Vertices à VERTEX_SIZE (gepacktes Format, siehe ChunkMesher). */
    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    /* Index = RenderLayer.ordinal(); Region null = Layer leer */
    private final VertexArena.Region[] regions = new VertexArena.Region[RenderLayer.VALUES.length];
    private final int[] quadCounts = new int[RenderLayer.VALUES.length];

    private final VertexArena.Region[] compactGeometry = new VertexArena.Region[3];
    private final VertexArena.Region[] compactShading = new VertexArena.Region[3];
    private final int[] compactQuadCounts = new int[3];
    private final int tintBase;

    /* Kleinvegetations-Segment (Gras/Blumen/Pilze): eigene Region in der CUTOUT-Arena,
       eigenes Draw-Segment im Renderer (distanzabhängige Ausdünnung/Skip). */
    private VertexArena.Region detailRegion;
    private int detailQuadCount;

    /* CPU-Kopie des Translucent-Layers für die Per-Quad-Sortierung (null ohne Translucent-Inhalt).
       Referenz auf das Mesher-Array — wird nach dem Upload sonst nirgends mehr benutzt. */
    private final int[] translucentData;

    /* Kameraposition (Welt) des letzten Quad-Sorts. NaN = noch nie sortiert. */
    private double lastSortX = Double.NaN, lastSortY, lastSortZ;
    private float lastSortYaw = Float.NaN, lastSortPitch;

    /* Wiederverwendete Sortier-Puffer — nur Render-Thread (sortTranslucent läuft nie parallel). */
    private static long[] sortKeys = new long[1024];
    private static int[] sortScratch = new int[1024 * QUAD_INTS];

    /** Alloziert die Regionen aller nicht-leeren Layer. Render-Thread. */
    public SectionMesh(int chunkX, int sectionY, int chunkZ, ChunkMesher.MeshData data,
                       VertexArena[] arenas, VertexArena[] compactGeometryArenas,
                       VertexArena[] compactShadingArenas, int tintBase) {
        this.chunkX = chunkX;
        this.sectionY = sectionY;
        this.chunkZ = chunkZ;
        this.tintBase = tintBase;
        this.translucentData = data.translucent;

        this.upload(RenderLayer.OPAQUE, data.opaque, arenas);
        this.upload(RenderLayer.CUTOUT, data.cutout, arenas);
        this.upload(RenderLayer.TRANSLUCENT, data.translucent, arenas);
        if (data.detail != null) {
            this.detailRegion = arenas[RenderLayer.CUTOUT.ordinal()].alloc(data.detail);
            this.detailQuadCount = data.detail.length / QUAD_INTS;
        }
        if (data.compactGeometry != null) {
            for (int mode = 0; mode < this.compactGeometry.length; mode++) {
                int[] geometry = data.compactGeometry[mode];
                if (geometry == null) continue;
                this.compactGeometry[mode] = compactGeometryArenas[mode].alloc(geometry);
                this.compactQuadCounts[mode] = geometry.length / PackedTerrainQuad.GEOMETRY_INTS;
                int[] shading = data.compactShading == null ? null : data.compactShading[mode];
                if (shading != null) this.compactShading[mode] = compactShadingArenas[mode].alloc(shading);
            }
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

    public boolean hasCompact(int mode) { return this.compactGeometry[mode] != null; }
    public int compactIndexCount(int mode) { return this.compactQuadCounts[mode] * 6; }
    public int compactGeometryBase(int mode) { return this.compactGeometry[mode].elementOffset(); }
    public int compactShadingBase(int mode) {
        VertexArena.Region region = this.compactShading[mode];
        return region == null ? 0 : region.elementOffset();
    }
    public int tintBase() { return this.tintBase; }

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
        int max = Math.max(Math.max(this.quadCounts[0], this.detailQuadCount),
                Math.max(this.quadCounts[1], this.quadCounts[2]));
        for (int count : this.compactQuadCounts) max = Math.max(max, count);
        return max;
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
     * Kamera seit dem letzten Sort relevant bewegt oder gedreht wurde. Render-Thread only.
     *
     * @return true, wenn tatsächlich neu sortiert wurde (fürs Frame-Budget im ChunkRenderer)
     */
    public boolean sortTranslucent(Camera camera, Vector3d direction, VertexArena arena, long currentFrame) {
        int[] data = this.translucentData;
        if (data == null) return false;

        Vector3d cam = camera.getPosition();
        double mx = cam.x - this.lastSortX, my = cam.y - this.lastSortY, mz = cam.z - this.lastSortZ;
        float yawDelta = angleDelta(camera.getYaw(), this.lastSortYaw);
        float pitchDelta = Math.abs(camera.getPitch() - this.lastSortPitch);
        /* Kleine Positionsänderungen bleiben gedrosselt; eine sichtbare Rotation sortiert neu. */
        if (mx * mx + my * my + mz * mz < 0.0625 && yawDelta < 2F && pitchDelta < 2F) return false;
        this.lastSortX = cam.x;
        this.lastSortY = cam.y;
        this.lastSortZ = cam.z;
        this.lastSortYaw = camera.getYaw();
        this.lastSortPitch = camera.getPitch();

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
            float depth = (float) (dx * direction.x + dy * direction.y + dz * direction.z);
            /* IEEE-Bits in monotonen unsigned Schlüssel wandeln; q stabilisiert Gleichstände. */
            int bits = Float.floatToRawIntBits(depth);
            int ordered = bits ^ ((bits >> 31) & 0x7FFFFFFF);
            sortKeys[q] = ((long) ordered << 32) | q;
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

    private static float angleDelta(float a, float b) {
        float delta = Math.abs(a - b) % 360F;
        return delta > 180F ? 360F - delta : delta;
    }

    /** Gibt alle Regionen deferred frei. */
    public void dispose(VertexArena[] arenas, VertexArena[] compactGeometryArenas,
                        VertexArena[] compactShadingArenas, long currentFrame) {
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
        for (int mode = 0; mode < this.compactGeometry.length; mode++) {
            if (this.compactGeometry[mode] != null) {
                compactGeometryArenas[mode].free(this.compactGeometry[mode], currentFrame);
                this.compactGeometry[mode] = null;
            }
            if (this.compactShading[mode] != null) {
                compactShadingArenas[mode].free(this.compactShading[mode], currentFrame);
                this.compactShading[mode] = null;
            }
        }
    }
}
