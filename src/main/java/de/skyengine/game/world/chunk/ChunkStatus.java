package de.skyengine.game.world.chunk;

/**
 * Lebenszyklus eines Chunks. Die Reihenfolge ist ein Lattice: jeder Status setzt alle
 * vorherigen voraus, Bereichs-Checks laufen über {@link #isAtLeast}.
 */
public enum ChunkStatus {
    NEW, GENERATING, GENERATED, DECORATING, DECORATED, MESHING, READY;

    /** true, wenn dieser Status im Lebenszyklus mindestens {@code other} erreicht hat. */
    public boolean isAtLeast(ChunkStatus other) {
        return this.ordinal() >= other.ordinal();
    }
}
