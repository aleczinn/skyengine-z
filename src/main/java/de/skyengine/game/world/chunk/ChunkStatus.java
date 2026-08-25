package de.skyengine.game.world.chunk;

/**
 * Lebenszyklus eines Chunks. Die Reihenfolge ist ein Lattice: jeder Status setzt alle
 * vorherigen voraus, Bereichs-Checks laufen über {@link #isAtLeast}.
 */
public enum ChunkStatus {
    /* LIGHTING/LIT liegen zwischen DECORATED und MESHING: der Licht-Job liest Blöcke über die
       Chunk-Ränder (Gate 8× DECORATED) und der Mesher liest Nachbar-LICHT fürs Corner-Smoothing
       (Gate 8× LIT). Die DECORATED-Lesegrenze in Dimension bleibt davon unberührt — sie liegt
       darunter. */
    NEW, GENERATING, GENERATED, DECORATING, DECORATED, LIGHTING, LIT, MESHING, READY;

    /** true, wenn dieser Status im Lebenszyklus mindestens {@code other} erreicht hat. */
    public boolean isAtLeast(ChunkStatus other) {
        return this.ordinal() >= other.ordinal();
    }
}
