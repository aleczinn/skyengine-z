package de.skyengine.game.world.block;

public enum RenderLayer {

    /** Voll deckend, schreibt Depth, cullt Nachbar-Faces. */
    OPAQUE,

    /** Alpha-Test (discard), z.B. Cross-Modelle, Blätter. */
    CUTOUT,

    /** Echtes Alpha-Blending, wird zuletzt und rückwärts sortiert gerendert (Glas, Eis, später Wasser). */
    TRANSLUCENT;

    public static final RenderLayer[] VALUES = values();
}