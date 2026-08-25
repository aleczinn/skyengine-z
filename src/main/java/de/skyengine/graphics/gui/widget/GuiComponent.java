package de.skyengine.graphics.gui.widget;

import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.Layoutable;
import de.skyengine.graphics.gui.text.RichText;

import java.util.List;

/**
 * Basis aller GUI-Widgets (Button, Slider, Label, ...). Koordinaten liegen im virtuellen
 * GUI-Raum (siehe {@link GuiManager}), Ursprung oben links, y nach unten.
 *
 * <p>Gerendert wird in <b>zwei Pässen</b>, damit der GuiScreen pro Frame nur je ein
 * begin/end-Paar von SpriteRenderer und FontRenderer braucht (Muster: DebugOverlay):
 * {@link #renderBackground} läuft im Sprite-Pass, {@link #renderText} im Font-Pass.
 *
 * <p>Event-Methoden geben {@code true} zurück, wenn das Event konsumiert wurde.
 */
public abstract class GuiComponent implements Layoutable {

    public float x, y, w, h;
    public boolean visible = true;
    public boolean enabled = true;

    protected boolean hovered;
    protected boolean focused;

    /* Optionaler Anker: der GuiScreen positioniert verankerte Top-Level-Komponenten
       nach init() automatisch (Labels unten rechts, zentrierte Stacks, ...). */
    private Anchor anchor;
    private float anchorPadX, anchorPadY;

    /* Optionaler Hover-Tooltip (Zeilen, bereits geparst) — gezeichnet vom GuiManager. */
    private List<RichText> tooltip;

    /** Verankert die Komponente — Layout übernimmt der GuiScreen nach init(). Chainable. */
    public GuiComponent anchor(Anchor anchor) {
        return this.anchor(anchor, 0, 0);
    }

    public GuiComponent anchor(Anchor anchor, float padX, float padY) {
        this.anchor = anchor;
        this.anchorPadX = padX;
        this.anchorPadY = padY;
        return this;
    }

    public boolean hasAnchor() {
        return this.anchor != null;
    }

    /** Layoutet die Komponente an ihrem gespeicherten Anker (vom GuiScreen gerufen). */
    public void applyAnchor(float vW, float vH) {
        this.layoutAt(this.anchor.resolveX(vW, this.width(), this.anchorPadX),
                this.anchor.resolveY(vH, this.height(), this.anchorPadY));
    }

    /**
     * Hover-Tooltip setzen ({@link RichText}-Markup, {@code \n} trennt Zeilen). Chainbar wie
     * {@link #anchor(Anchor)}, damit bestehende Konstruktoren unverändert bleiben.
     */
    public GuiComponent tooltip(String markup) {
        this.tooltip = markup == null || markup.isEmpty() ? null : RichText.parseLines(markup);
        return this;
    }

    /**
     * Tooltip-Zeilen unter der Maus oder null. Überschreibbar für Widgets, deren Tooltip vom
     * aktuellen Wert abhängt (z.B. {@link CycleButton}).
     */
    public List<RichText> tooltip() {
        return this.tooltip;
    }

    public boolean isHovered() {
        return this.hovered;
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    /** Vom GuiScreen einmal pro Frame vor dem Rendern aufgerufen. */
    public void updateHover(double mx, double my) {
        this.hovered = this.enabled && this.isMouseOver(mx, my);
    }

    /** Sprite-Pass (SpriteRenderer ist bereits im begin-Zustand). */
    public void renderBackground(GuiManager gui, double mx, double my) {}

    /** Font-Pass (FontRenderer ist bereits im begin-Zustand). */
    public void renderText(GuiManager gui, double mx, double my) {}

    public boolean mousePressed(double mx, double my, int button) {
        return false;
    }

    /** @return true, wenn dieses Release eine abgeschlossene Widget-Aktion ausgeloest hat. */
    public boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    public void mouseDragged(double mx, double my, int button) {}

    public boolean mouseScrolled(double mx, double my, double amount) {
        return false;
    }

    public boolean keyPressed(int key) {
        return false;
    }

    public boolean charTyped(int codepoint) {
        return false;
    }

    /** Fokussierbare Widgets (z.B. Textfelder) erhalten Tastatur-/Char-Events. */
    public boolean isFocusable() {
        return false;
    }

    public boolean isFocused() {
        return this.focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    /**
     * Sammelt die event-/renderfähigen Blatt-Widgets ein. Default: das Widget selbst;
     * Layout-Container (Stacks) rekursieren stattdessen in ihre Kinder.
     */
    public void collectLeaves(java.util.List<GuiComponent> out) {
        out.add(this);
    }

    /* --- Layoutable --- */

    @Override
    public float width() {
        return this.w;
    }

    @Override
    public float height() {
        return this.h;
    }

    @Override
    public void layoutAt(float x, float y) {
        this.x = x;
        this.y = y;
    }
}
