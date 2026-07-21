package de.skyengine.graphics.gui.layout;

import de.skyengine.graphics.gui.widget.GuiComponent;

/**
 * Horizontaler Stapel: positioniert Kinder nebeneinander mit festem Abstand ({@code gap}).
 * Die Höhe des Stacks ist die des höchsten Kindes; Kinder werden vertikal darin ausgerichtet.
 * Nutzung/Verankerung: siehe {@link Stack}.
 */
public final class HStack extends Stack {

    public enum Align { TOP, CENTER, BOTTOM }

    private Align align = Align.CENTER;

    /** Bequemform mit Default-Abstand ({@link Stack#DEFAULT_GAP}). */
    public HStack(GuiComponent... children) {
        super(DEFAULT_GAP, children);
    }

    public HStack(float gap, GuiComponent... children) {
        super(gap, children);
    }

    public HStack align(Align align) {
        this.align = align;
        return this;
    }

    @Override
    public float width() {
        if (this.children.isEmpty()) return 0;
        float sum = 0;
        for (GuiComponent c : this.children) sum += c.width();
        return sum + this.gap * (this.children.size() - 1);
    }

    @Override
    public float height() {
        float max = 0;
        for (GuiComponent c : this.children) max = Math.max(max, c.height());
        return max;
    }

    @Override
    public void layoutAt(float x, float y) {
        this.applyOwnBounds(x, y);
        float h = this.height();
        float cx = x;
        for (GuiComponent c : this.children) {
            float cy = switch (this.align) {
                case TOP -> y;
                case CENTER -> y + (h - c.height()) / 2f;
                case BOTTOM -> y + h - c.height();
            };
            c.layoutAt(cx, cy);
            cx += c.width() + this.gap;
        }
    }
}
