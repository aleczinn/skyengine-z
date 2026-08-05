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
    public static final int TICKS_RANDOMLY   = 1 << 7; // Phase 1.1 (Bit 5-6 sind der Layer)
    public static final int FLUID            = 1 << 8; // Wasser/Lava: dynamische Geometrie im Mesher
    public static final int NO_LOD_SURFACE   = 1 << 9; // nie als LOD-Terrain-Oberfläche (Logs)
    /* Wirft AO/verschattet Ecklicht im Mesher. Default = OPAQUE_CUBE; getrennt, damit ein
       Nicht-Vollwürfel (ausgefahrene Kolben-Basis) verschatten kann, ohne Nachbarn zu cullen. */
    public static final int AO_OCCLUDER      = 1 << 18;
    public static final int LEAVES           = 1 << 25; // Laub (LeavesQuality-LOW-Culling)

    /* Render-Layer in 2 Bits (Bit 5-6). */
    private static final int LAYER_SHIFT = 5;
    private static final int LAYER_MASK = 0b11 << LAYER_SHIFT;

    /* Licht-Opazität 0..15 in Bits 10-13: wie viel Himmelslicht der Block je Zelle schluckt
       (0 = durchlässig wie Glas, 1 = dämpfend wie Wasser/Laub, 15 = opak).
       Bits 19-24 und 26-31 sind frei. */
    private static final int OPACITY_SHIFT = 10;
    private static final int OPACITY_MASK = 0b1111 << OPACITY_SHIFT;

    /* Eigenleuchten (Luminanz) 0..15 in Bits 14-17: wie hell der Block selbst strahlt
       (0 = leuchtet nicht, Fackel 14, Lava 15). Monochrom — die Lichtfarbe hätte 24 Bit
       gebraucht und liegt deshalb in BlockConfig, nicht hier. */
    private static final int LUMINANCE_SHIFT = 14;
    private static final int LUMINANCE_MASK = 0b1111 << LUMINANCE_SHIFT;

    public static int packLayer(int flags, RenderLayer layer) {
        return (flags & ~LAYER_MASK) | (layer.ordinal() << LAYER_SHIFT);
    }

    public static RenderLayer layer(int flags) {
        return RenderLayer.VALUES[(flags & LAYER_MASK) >>> LAYER_SHIFT];
    }

    public static int packOpacity(int flags, int opacity) {
        return (flags & ~OPACITY_MASK) | ((opacity & 0b1111) << OPACITY_SHIFT);
    }

    public static int opacity(int flags) {
        return (flags & OPACITY_MASK) >>> OPACITY_SHIFT;
    }

    public static int packLuminance(int flags, int luminance) {
        return (flags & ~LUMINANCE_MASK) | ((luminance & 0b1111) << LUMINANCE_SHIFT);
    }

    public static int luminance(int flags) {
        return (flags & LUMINANCE_MASK) >>> LUMINANCE_SHIFT;
    }

    private StateFlags() {}
}