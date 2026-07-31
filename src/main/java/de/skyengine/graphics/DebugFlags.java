package de.skyengine.graphics;

/**
 * Transiente Debug-Schalter (nur Session, NICHT in options.json). Vom {@code GuiDebugScreen}
 * und dem Render-/Input-Pfad gelesen/geschrieben. GPU-Cull ({@code GpuCull.ENABLED/DEBUG_TINT})
 * und {@code LodMesher.EMIT_GRASS_OVERLAY} liegen bewusst in ihren Subsystemen; hier nur, was
 * sonst nirgends ein Zuhause hat.
 */
public final class DebugFlags {

    /** Welt als Wireframe zeichnen (renderWorld setzt den GL-Line-Mode eng um den Welt-Draw). */
    public static volatile boolean wireframe = false;

    /** Entity-Hitboxen zeichnen — Gerüst (per F3+H umschaltbar); Rendering folgt später. */
    public static volatile boolean entityHitboxes = false;

    /** Chunk-Grenzen (per F3+G): 0 = aus, 1 = ganzer Chunk, 2 = Chunk + nicht-leere Sections. */
    public static volatile int chunkBorders = 0;

    /**
     * LOD-Opaque im CPU-Pfad pro Level in eigene Sub-Draws aufteilen (Mess-Gate für die
     * per-Level-GPU-Queries lodO1..lodO5). Hing früher direkt an {@code FrameProfiler
     * .isEnabled()} — dadurch zeichnete der CPU-Pfad unter DebugMode.FULL bis zu 5 Sub-Draws,
     * der GPU-Pfad (gemergtes LOD-Segment) nur einen: jeder CPU-vs-GPU-Vergleich war
     * dadurch verzerrt. Jetzt eigener Schalter, Default AUS.
     */
    public static volatile boolean lodLevelSplit = false;

    private DebugFlags() {}
}
