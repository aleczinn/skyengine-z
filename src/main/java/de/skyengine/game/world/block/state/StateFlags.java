package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.RenderLayer;

/**
 * Gepackte Per-State-Flags für den Hot-Path (Mesher, Kollision). Statt pro Abfrage
 * virtuell über {@link de.skyengine.game.world.block.Block} zu dispatchen, werden
 * die Werte einmalig beim {@code BlockRegistry.bake()} berechnet und als ein int
 * pro {@link BlockState} abgelegt. Abfragen sind dann reine Bit-Tests.
 */
public final class StateFlags {

    public static final int OPAQUE_CUBE      = 1;
    public static final int SOLID            = 1 << 1;
    public static final int CULL_SAME        = 1 << 2;
    public static final int RANDOM_OFFSET    = 1 << 3;
    public static final int HAS_BLOCK_ENTITY = 1 << 4; // Phase 4d

    /* Render-Layer in 2 Bits (Bit 5-6). */
    private static final int LAYER_SHIFT = 5;
    private static final int LAYER_MASK = 0b11 << LAYER_SHIFT;

    public static int packLayer(int flags, RenderLayer layer) {
        return (flags & ~LAYER_MASK) | (layer.ordinal() << LAYER_SHIFT);
    }

    public static RenderLayer layer(int flags) {
        return RenderLayer.VALUES[(flags & LAYER_MASK) >>> LAYER_SHIFT];
    }

    private StateFlags() {}
}