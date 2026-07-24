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

    private DebugFlags() {}
}
