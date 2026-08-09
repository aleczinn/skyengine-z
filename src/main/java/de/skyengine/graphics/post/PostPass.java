package de.skyengine.graphics.post;

import de.skyengine.core.io.IDisposable;

/**
 * Ein Schritt der Post-Processing-Kette. Der {@link PostProcessor} setzt vor jedem
 * {@link #execute(PostContext)} die Verkettungsfelder des Contexts ({@code input},
 * {@code targetFbo}); der Pass liest seine Eingänge ausschließlich aus dem Context
 * (explizite Inputs/Outputs — RenderGraph-Denke ohne Graph-Overhead).
 *
 * <p>Spätere Pässe (AutoExposurePass, LUTPass — Slot nach Grading/vor AA —,
 * SSRPass, TAA im AA-Pass) werden nur der Pass-Liste hinzugefügt; die Kette selbst
 * bleibt unangetastet.
 */
public interface PostPass extends IDisposable {

    /** Einmalig auf dem Render-Thread (GL-Kontext aktiv), nach {@link PostContext}-Aufbau. */
    void init(PostContext context);

    /** Fenstergröße geändert — Context-Targets sind bereits neu angelegt. */
    default void resize(PostContext context) {}

    /** false = Pass wird übersprungen (zählt auch nicht als letzter Pass der Kette). */
    boolean isActive(PostContext context);

    /** Zeichnet von {@code context.input} nach {@code context.targetFbo} (0 = GuiScreen). */
    void execute(PostContext context);
}
