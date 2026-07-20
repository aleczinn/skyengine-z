package de.skyengine.graphics.gui.layout;

import de.skyengine.graphics.gui.widget.GuiComponent;

import java.util.ArrayList;
import java.util.List;

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

    private Anchor anchor;
    private float anchorPadX, anchorPadY;

    protected Stack(float gap, GuiComponent... children) {
        this.gap = gap;
        for (GuiComponent child : children) {
            this.children.add(child);
        }
    }

    public Stack add(GuiComponent child) {
        this.children.add(child);
        return this;
    }

    /** Verankert den Stack — der GuiScreen layoutet ihn nach init() automatisch. */
    public Stack anchor(Anchor anchor) {
        return this.anchor(anchor, 0, 0);
    }

    public Stack anchor(Anchor anchor, float padX, float padY) {
        this.anchor = anchor;
        this.anchorPadX = padX;
        this.anchorPadY = padY;
        return this;
    }

    public boolean hasAnchor() {
        return this.anchor != null;
    }

    /** Layoutet den Stack an seinem gespeicherten Anker (vom GuiScreen nach init() gerufen). */
    public void applyAnchor(float vW, float vH) {
        this.layoutAt(this.anchor.resolveX(vW, this.width(), this.anchorPadX),
                this.anchor.resolveY(vH, this.height(), this.anchorPadY));
    }

    /** Einmaliges manuelles Anker-Layout ohne gespeicherten Anker. */
    public void layoutAnchored(float vW, float vH, Anchor anchor, float padX, float padY) {
        this.layoutAt(anchor.resolveX(vW, this.width(), padX), anchor.resolveY(vH, this.height(), padY));
    }

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
