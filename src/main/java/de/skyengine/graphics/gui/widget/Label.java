package de.skyengine.graphics.gui.widget;

import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;

/**
 * Statischer Text (nur Font-Pass). Größe = Zeilenhöhe in virtuellen Pixeln;
 * Breite/Höhe werden fürs Layout aus dem Text abgeleitet, sobald der GuiScreen sie setzt.
 */
public final class Label extends GuiComponent {

    private static final Color4 DEFAULT_COLOR = new Color4(1f, 1f, 1f, 1f);

    private final String text;
    private final float size;
    private final Color4 color;
    private final boolean shadow;

    public Label(String text, float size) {
        this(text, size, DEFAULT_COLOR, true);
    }

    public Label(String text, float size, Color4 color, boolean shadow) {
        this.text = text;
        this.size = size;
        this.color = color;
        this.shadow = shadow;
        this.h = size;
        /* Breite kennt erst der FontRenderer — wird beim ersten Layout über measure() gesetzt. */
    }

    /** Misst die Textbreite (fürs Layout VOR dem ersten Render nötig). */
    public Label measure(GuiManager gui) {
        this.w = gui.font().getStringWidth(this.text, this.size);
        return this;
    }

    @Override
    public void renderText(GuiManager gui, double mx, double my) {
        if (this.shadow) {
            gui.font().drawStringWithShadow(this.text, this.x, this.y, this.size, this.color);
        } else {
            gui.font().drawString(this.text, this.x, this.y, this.size, this.color);
        }
    }
}
