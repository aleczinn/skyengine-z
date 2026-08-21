package de.skyengine.graphics.gui.layout;

import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.Spacer;

import java.util.ArrayList;
import java.util.List;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Basis der Layout-Container ({@link VStack}/{@link HStack}): ein {@link GuiComponent}, der
 * selbst nichts zeichnet und keine Events behandelt — er positioniert nur seine Kinder.
 * Events/Rendering laufen über die abgeflachte Blatt-Liste des Screens
 * ({@link GuiComponent#collectLeaves}); Stacks sind darin unsichtbar.
 *
 * <p>Deklarative Nutzung im Screen (Kinder NICHT zusätzlich einzeln registrieren):
 * <pre>{@code
 * this.components.add(new VStack(8,
 *         title,
 *         new HStack(4, links, rechts)   // nebeneinander
 * ).anchor(Anchor.CENTER));
 * }</pre>
 * Der GuiScreen layoutet verankerte Stacks nach {@code init()} automatisch
 * ({@link #applyAnchor}); alternativ manuell über {@link #layoutAt}.
 */
public abstract class Stack extends GuiComponent {

    public static final float DEFAULT_GAP = 4;

    protected final List<GuiComponent> children = new ArrayList<>();
    protected final float gap;

    protected Stack(float gap, GuiComponent... children) {
        this.gap = gap;
        for (GuiComponent child : children) {
            if (child == null) {
                child = new Spacer(CELL_W, CELL_H);
            }
            this.children.add(child);
        }
    }

    public Stack add(GuiComponent child) {
        this.children.add(child);
        return this;
    }

    /* Die Anker-Mechanik (anchor/hasAnchor/applyAnchor) kommt aus GuiComponent —
       sie gilt für ALLE Widgets, nicht nur Stacks (z.B. Labels unten rechts). */

    /** Stacks sind reine Layout-Knoten: nur die Blätter landen in der Event-/Render-Liste. */
    @Override
    public void collectLeaves(List<GuiComponent> out) {
        for (GuiComponent child : this.children) {
            child.collectLeaves(out);
        }
    }

    @Override
    public abstract float width();

    @Override
    public abstract float height();

    @Override
    public abstract void layoutAt(float x, float y);

    /** Eigene Maße/Position nach dem Kind-Layout festhalten (für verschachtelte Stacks). */
    protected final void applyOwnBounds(float x, float y) {
        this.x = x;
        this.y = y;
        this.w = this.width();
        this.h = this.height();
    }
}
