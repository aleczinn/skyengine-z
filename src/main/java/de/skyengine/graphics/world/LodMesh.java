package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.ChunkMesher;

/**
 * LOD-Regionen-Mesh als Mieter der OPAQUE-{@link VertexArena}: eine Region (128x128 Blöcke)
 * Heightmap-Surface-Geometrie im gepackten Chunk-Vertex-Format. Gezeichnet wird sie im
 * {@link ChunkRenderer} als zusätzlicher Indirect-Command im bestehenden OPAQUE-Segment —
 * kein eigener VAO, kein eigener Shader, kein eigener Draw-Call.
 */
public class LodMesh {

    public final int rx, rz;      // Regionskoordinaten (1 Region = 128 Blöcke)
    public final int level;       // LOD-Level (Zellgröße 2^level Blöcke)
    /* Y-Basis: Vertices sind relativ dazu gepackt (u16 trägt nur ~254 Blöcke Spanne);
       der Renderer addiert yBase im Draw-Offset. */
    public final int yBase;
    public final float minY, maxY; // absoluter y-Bereich (fürs Frustum-AABB)

    private final VertexArena.Region region;
    private final int quadCount;

    /** Alloziert die Arena-Region und lädt die Mesh-Daten hoch. Render-Thread. */
    public LodMesh(int rx, int rz, int level, int yBase, int[] data, float minY, float maxY, VertexArena arena) {
        this.rx = rx;
        this.rz = rz;
        this.level = level;
        this.yBase = yBase;
        this.minY = minY;
        this.maxY = maxY;
        this.quadCount = data.length / (4 * ChunkMesher.VERTEX_SIZE);
        this.region = arena.alloc(data);
    }

    /** baseVertex für den Indirect-Command. */
    public int baseVertex() {
        return this.region.vertexOffset();
    }

    /** Index-Anzahl für den Indirect-Command (Quads · 6). */
    public int indexCount() {
        return this.quadCount * 6;
    }

    public int quadCount() {
        return this.quadCount;
    }

    /** Gibt die Arena-Region deferred frei. */
    public void dispose(VertexArena arena, long currentFrame) {
        arena.free(this.region, currentFrame);
    }
}
