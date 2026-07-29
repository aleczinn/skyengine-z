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

    /**
     * <b>Gepacktes</b> Licht mit derselben Nachbar-Auflösung wie {@link #sample}: Himmelslicht in
     * Bits 0-3, Blocklicht in Bits 4-7, beide 0..15. Abweichende Randfälle: über der Welt ist
     * voller Himmel (15), unter ihr Dunkelheit (0), und ein fehlender Nachbar-Chunk gilt als
     * Himmel — sonst bekäme jede Ladekante einen schwarzen Saum, obwohl der Chunk gleich hell
     * nachgeladen wird.
     *
     * <p>Die Randkonstanten stimmen gepackt unverändert weiter: {@code 15} heißt „Himmel 15,
     * Blocklicht 0" und {@code 0} heißt „beides aus" — genau das, was dort jeweils gelten soll.
     * Eine fehlende Nachbarzelle darf nämlich hell sein, aber nicht glühen.</p>
     */
    public static int samplePackedLight(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east,
                                        Chunk[] diagonals, int x, int y, int z) {
        if (y >= Chunk.HEIGHT) return 15;
        if (y < 0) return 0;
        int size = ChunkSection.SIZE;
        if (x < 0 || x >= size) {
            if (z < 0 || z >= size) { // Diagonal-Ecke über zwei Chunk-Grenzen
                Chunk c = diagonals[(z < 0 ? 0 : 2) + (x < 0 ? 0 : 1)];
                return c != null ? packed(c, x < 0 ? size - 1 : 0, y, z < 0 ? size - 1 : 0) : 15;
            }
            Chunk c = x < 0 ? west : east;
            return c != null ? packed(c, x < 0 ? size - 1 : 0, y, z) : 15;
        }
        if (z < 0 || z >= size) {
            Chunk c = z < 0 ? north : south;
            return c != null ? packed(c, x, y, z < 0 ? size - 1 : 0) : 15;
        }
        return packed(chunk, x, y, z);
    }

    private static int packed(Chunk chunk, int x, int y, int z) {
        return chunk.light.get(x, y, z) | (chunk.blockLight.get(x, y, z) << 4);
    }

    private NeighborSampler() {}
}
