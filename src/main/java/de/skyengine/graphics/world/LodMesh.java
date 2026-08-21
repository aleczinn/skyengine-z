package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.lod.LodMesher;

/**
 * LOD-Regionen-Mesh: eine Region (128x128 Blöcke) Heightmap-Surface-Geometrie im gepackten
 * Chunk-Vertex-Format, aufgeteilt in eine Opaque- (Terrain + Wände) und eine Translucent-
 * Region (Fluid-Top-Quads). Beide mieten in den dedizierten LOD-{@link VertexArena}s des
 * {@link ChunkRenderer} und werden dort über eigene glMultiDrawElementsIndirect-Calls
 * gezeichnet (keine Section-Arena, kein Section-VAO). Grosse Regionen werden in begrenzte
 * Draw-Slices zerlegt; alle Slices bleiben in derselben zusammenhaengenden Arena-Region.
 */
public class LodMesh {

    /**
     * Harte Obergrenze eines einzelnen LOD-Draws. Der LOD-EBO enthaelt genau so viele
     * nullbasierte Quad-Indizes; groessere Regionen werden ueber verschobene baseVertex-Slices
     * gezeichnet, damit kein Draw jemals hinter das gueltige Indexfenster greifen kann.
     *
     * <p><b>Der Wert ist gemessen, nicht geraten — NICHT hochsetzen.</b> Ein einzelner
     * LOD-Draw rendert Muell, sobald sein {@code count} ueber ~2^17 = 131072 Indizes geht;
     * sichtbar als lange Splitter-Dreiecke und Loecher quer durch das Fern-Terrain. Bisektion
     * an einer realen Bergregion (Seed 187, rd=16/lodMax=128, GPU-Cull aus, AA egal):
     * 18932 Quads/Draw (113592 Indizes) sauber, 25480 (152880) kaputt, Slice-Limit 24000
     * kaputt, Slice-Limit 16384 sauber. Die Mesh-Daten sind in BEIDEN Faellen beweisbar
     * identisch (gleiche Flaechen, gleiche Wicklung, Arena-Inhalt bit-gleich zum Upload) —
     * es ist ausschliesslich die Draw-Groesse. 16384 Quads = 98304 Indizes lassen reichlich
     * Marge. Aufgefallen ist das erst mit L1-Corner-AO, weil erst dessen feine Wandzellen
     * einzelne Regionen ueber 20000 Quads treiben.
     */
    public static final int MAX_QUADS_PER_DRAW = 16384;

    private static final int QUAD_VERTICES = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int QUAD_INTS = QUAD_VERTICES * ChunkMesher.VERTEX_SIZE;

    public final int rx, rz;      // Regionskoordinaten (Ecke, in 128er-Regionen)
    public final int level;       // LOD-Level (Zellgröße 2^level Blöcke)
    /* Footprint: 128 (normal) oder 512 Blöcke (4x4-Superregion) — bestimmt Frustum-AABB,
       und die per-Draw-Positions-Skala (normale Regionen 1/127, Superregionen 1/64). */
    public final int sizeBlocks;
    public final float invPosScale;
    public final int posScaleCode;
    /** 16-Bit-Chunkmaske, mit der genau dieses hochgeladene Mesh geclippt wurde. */
    public final int mask;
    /** Nur Render-Thread: sichtbare LOD-Zellen, unter denen bereits ein interaktives L0 liegt. */
    int debugConflictMask;
    /* Y-Basis: Vertices sind relativ dazu gepackt (u16 trägt nur ~254/1023 Blöcke Spanne);
       der Renderer addiert yBase im Draw-Offset. */
    public final int yBase;
    public final float minY, maxY; // absoluter y-Bereich (fürs Frustum-AABB)

    /* Descriptor-Slots der Opaque-Slices im GPU-Cull-Substrat (-1 = nicht registriert),
       gepflegt vom ChunkRenderer. Translucent bleibt wie bisher im CPU-Pfad. */
    final int[] gpuSlots;

    private final VertexArena.Region opaqueRegion;       // null = keine Opaque-Geometrie
    private final VertexArena.Region translucentRegion;  // null = keine Translucent-Geometrie
    private final int opaqueQuadCount, translucentQuadCount;

    /** Alloziert die Arena-Regionen und lädt die Mesh-Daten hoch. Render-Thread. */
    public LodMesh(int rx, int rz, int level, int sizeRegions, int mask, int yBase,
                   int[] opaqueData, int[] translucentData, float minY, float maxY,
                   VertexArena opaqueArena, VertexArena translucentArena) {
        this.rx = rx;
        this.rz = rz;
        this.level = level;
        this.sizeBlocks = sizeRegions * LodMesher.REGION_BLOCKS;
        float posScale = LodMesher.posScaleFor(sizeRegions);
        this.invPosScale = 1F / posScale;
        this.posScaleCode = sizeRegions > 1
                ? DrawMetadata.LOD_SUPER_SCALE_CODE : DrawMetadata.LOD_REGION_SCALE_CODE;
        this.mask = mask & 0xFFFF;
        this.yBase = yBase;
        this.minY = minY;
        this.maxY = maxY;
        requireCompleteQuads("opaque", opaqueData);
        requireCompleteQuads("translucent", translucentData);
        this.opaqueQuadCount = opaqueData.length / QUAD_INTS;
        this.opaqueRegion = this.opaqueQuadCount > 0 ? opaqueArena.alloc(opaqueData) : null;
        this.translucentQuadCount = translucentData.length / QUAD_INTS;
        this.translucentRegion = this.translucentQuadCount > 0 ? translucentArena.alloc(translucentData) : null;
        this.gpuSlots = new int[drawCountForQuads(this.opaqueQuadCount)];
        java.util.Arrays.fill(this.gpuSlots, -1);
    }

    public boolean hasOpaque() {
        return this.opaqueRegion != null;
    }

    public boolean hasTranslucent() {
        return this.translucentRegion != null;
    }

    public int opaqueDrawCount() {
        return this.gpuSlots.length;
    }

    /** baseVertex des {@code draw}-ten Opaque-Slices. */
    public int baseVertexOpaque(int draw) {
        return this.opaqueRegion.vertexOffset() + drawStartQuad(draw) * QUAD_VERTICES;
    }

    /** Index-Anzahl des {@code draw}-ten Opaque-Slices. */
    public int indexCountOpaque(int draw) {
        return drawQuadCount(this.opaqueQuadCount, draw) * INDICES_PER_QUAD;
    }

    public int translucentDrawCount() {
        return drawCountForQuads(this.translucentQuadCount);
    }

    /** baseVertex des {@code draw}-ten Translucent-Slices. */
    public int baseVertexTranslucent(int draw) {
        return this.translucentRegion.vertexOffset() + drawStartQuad(draw) * QUAD_VERTICES;
    }

    /** Index-Anzahl des {@code draw}-ten Translucent-Slices. */
    public int indexCountTranslucent(int draw) {
        return drawQuadCount(this.translucentQuadCount, draw) * INDICES_PER_QUAD;
    }

    /** Gesamt-Quad-Zahl (nur für Debug-Statistik). */
    public int quadCount() {
        return this.opaqueQuadCount + this.translucentQuadCount;
    }

    static int drawCountForQuads(int quads) {
        if (quads < 0) throw new IllegalArgumentException("Negative Quad-Zahl: " + quads);
        return quads == 0 ? 0 : (quads - 1) / MAX_QUADS_PER_DRAW + 1;
    }

    static int drawStartQuad(int draw) {
        if (draw < 0) throw new IndexOutOfBoundsException("Negativer LOD-Draw: " + draw);
        return Math.multiplyExact(draw, MAX_QUADS_PER_DRAW);
    }

    static int drawQuadCount(int totalQuads, int draw) {
        int start = drawStartQuad(draw);
        if (start >= totalQuads) {
            throw new IndexOutOfBoundsException("LOD-Draw " + draw + " ausserhalb von " + totalQuads + " Quads");
        }
        return Math.min(MAX_QUADS_PER_DRAW, totalQuads - start);
    }

    private static void requireCompleteQuads(String layer, int[] data) {
        if (data.length % QUAD_INTS != 0) {
            throw new IllegalArgumentException("Unvollstaendige LOD-" + layer + "-Geometrie: "
                    + data.length + " ints sind kein Vielfaches von " + QUAD_INTS);
        }
    }

    /** Gibt beide Arena-Regionen deferred frei. */
    public void dispose(VertexArena opaqueArena, VertexArena translucentArena, long currentFrame) {
        if (this.opaqueRegion != null) opaqueArena.free(this.opaqueRegion, currentFrame);
        if (this.translucentRegion != null) translucentArena.free(this.translucentRegion, currentFrame);
    }
}
