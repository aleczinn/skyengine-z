package de.skyengine.game.world.chunk;

/**
 * Geteilter Block-Zugriff über Chunk-Grenzen für Mesh-/Fluid-Sampling: section-lokale x/z
 * dürfen -1..SIZE sein und werden über die 4 Kardinal- und 4 Diagonal-Nachbarn aufgelöst.
 *
 * <p><b>Diagonal-Konvention:</b> {@code diagonals} in Reihenfolge NW, NE, SW, SE — exakt so
 * liefert sie {@code ChunkManager.getDiagonalsAtLeast}. {@link ChunkMesher} und
 * {@link FluidGeometry} MÜSSEN dieselbe Auflösung sehen, sonst berechnen die vier an einer
 * Chunk-Ecke angrenzenden Zellen die gemeinsame Fluid-Eckhöhe unterschiedlich und die
 * Flächen klaffen auseinander. Außerhalb geladener Chunks zählt alles als Luft (0).
 */
public final class NeighborSampler {

    public static int sample(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east,
                             Chunk[] diagonals, int x, int y, int z) {
        int size = ChunkSection.SIZE;
        if (x < 0 || x >= size) {
            if (z < 0 || z >= size) { // Diagonal-Ecke über zwei Chunk-Grenzen
                Chunk c = diagonals[(z < 0 ? 0 : 2) + (x < 0 ? 0 : 1)];
                return c != null ? c.getBlock(x < 0 ? size - 1 : 0, y, z < 0 ? size - 1 : 0) : 0;
            }
            Chunk c = x < 0 ? west : east;
            return c != null ? c.getBlock(x < 0 ? size - 1 : 0, y, z) : 0;
        }
        if (z < 0 || z >= size) {
            Chunk c = z < 0 ? north : south;
            return c != null ? c.getBlock(x, y, z < 0 ? size - 1 : 0) : 0;
        }
        return chunk.getBlock(x, y, z);
    }

    private NeighborSampler() {}
}
