package de.skyengine.graphics;

/**
 * Transiente Debug-Schalter (nur Session, NICHT in options.json). Vom {@code GuiDebugScreen}
 * und dem Render-/Input-Pfad gelesen/geschrieben.
 */
public final class DebugFlags {

    /** Welt als Wireframe zeichnen (per F3+V oder Debug-Menü umschaltbar). */
    public static volatile boolean wireframe = false;

    /** Entity-Hitboxen und Blickrichtungen zeichnen (per F3+B umschaltbar). */
    public static volatile boolean entityHitboxes = false;

    /** Chunk-Grenzen (per F3+G): 0 = aus, 1 = ganzer Chunk, 2 = Chunk + nicht-leere Sections. */
    public static volatile int chunkBorders = 0;

    /**
     * Trefferflächen der Inventar-Slots einfärben (jeder Slot eine eigene Farbe). Macht tote
     * Zonen zwischen den Slots sichtbar — dort ginge ein Ablegen ins Leere.
     */
    public static volatile boolean guiSlotBounds = false;

    /**
     * Visueller Unterwasser-Nebel. Bleibt als Debug-Hilfe getrennt von Wasserphysik,
     * Unterwasser-Audio und dem tickbasierten Water-Vision-Verlauf.
     */
    public static volatile boolean underwaterEffect = true;

    private DebugFlags() {}
}
