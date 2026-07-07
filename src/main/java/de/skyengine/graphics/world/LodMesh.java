package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.ChunkMesher;

/**
 * LOD-Regionen-Mesh: eine Region (128x128 Blöcke) Heightmap-Surface-Geometrie im gepackten
 * Chunk-Vertex-Format, aufgeteilt in eine Opaque- (Terrain + Wände) und eine Translucent-
 * Region (Fluid-Top-Quads). Beide mieten in den dedizierten LOD-{@link VertexArena}s des
 * {@link ChunkRenderer} und werden dort über eigene glMultiDrawElementsIndirect-Calls
 * gezeichnet (keine Section-Arena, kein Section-VAO).
 */
public class LodMesh {

    public final int rx, rz;      // Regionskoordinaten (1 Region = 128 Blöcke)
    public final int level;       // LOD-Level (Zellgröße 2^level Blöcke)
    /* Y-Basis: Vertices sind relativ dazu gepackt (u16 trägt nur ~254 Blöcke Spanne);
       der Renderer addiert yBase im Draw-Offset. */
    public final int yBase;
    public final float minY, maxY; // absoluter y-Bereich (fürs Frustum-AABB)

    private final VertexArena.Region opaqueRegion;       // null = keine Opaque-Geometrie
    private final VertexArena.Region translucentRegion;  // null = keine Translucent-Geometrie
    private final int opaqueQuadCount, translucentQuadCount;

    /** Alloziert die Arena-Regionen und lädt die Mesh-Daten hoch. Render-Thread. */
    public LodMesh(int rx, int rz, int level, int yBase, int[] opaqueData, int[] translucentData,
                   float minY, float maxY, VertexArena opaqueArena, VertexArena translucentArena) {
        this.rx = rx;
        this.rz = rz;
        this.level = level;
        this.yBase = yBase;
        this.minY = minY;
        this.maxY = maxY;
        this.opaqueQuadCount = opaqueData.length / (4 * ChunkMesher.VERTEX_SIZE);
        this.opaqueRegion = this.opaqueQuadCount > 0 ? opaqueArena.alloc(opaqueData) : null;
        this.translucentQuadCount = translucentData.length / (4 * ChunkMesher.VERTEX_SIZE);
        this.translucentRegion = this.translucentQuadCount > 0 ? translucentArena.alloc(translucentData) : null;
    }

    public boolean hasOpaque() {
        return this.opaqueRegion != null;
    }

    public boolean hasTranslucent() {
        return this.translucentRegion != null;
    }

    /** baseVertex für den Opaque-Indirect-Command. */
    public int baseVertexOpaque() {
        return this.opaqueRegion.vertexOffset();
    }

    /** Index-Anzahl für den Opaque-Indirect-Command (Quads · 6). */
    public int indexCountOpaque() {
        return this.opaqueQuadCount * 6;
    }

    /** baseVertex für den Translucent-Indirect-Command. */
    public int baseVertexTranslucent() {
        return this.translucentRegion.vertexOffset();
    }

    /** Index-Anzahl für den Translucent-Indirect-Command (Quads · 6). */
    public int indexCountTranslucent() {
        return this.translucentQuadCount * 6;
    }

    /** Größte Quad-Zahl der beiden Regionen (für die Index-Buffer-Kapazität). */
    public int maxQuads() {
        return Math.max(this.opaqueQuadCount, this.translucentQuadCount);
    }

    /** Gesamt-Quad-Zahl (nur für Debug-Statistik). */
    public int quadCount() {
        return this.opaqueQuadCount + this.translucentQuadCount;
    }

    /** Gibt beide Arena-Regionen deferred frei. */
    public void dispose(VertexArena opaqueArena, VertexArena translucentArena, long currentFrame) {
        if (this.opaqueRegion != null) opaqueArena.free(this.opaqueRegion, currentFrame);
        if (this.translucentRegion != null) translucentArena.free(this.translucentRegion, currentFrame);
    }
}
