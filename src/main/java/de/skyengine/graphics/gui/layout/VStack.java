package de.skyengine.graphics.gui.layout;

import de.skyengine.graphics.gui.widget.GuiComponent;

/**
 * Vertikaler Stapel: positioniert Kinder untereinander mit festem Abstand ({@code gap}).
 * Kinder behalten ihre eigene Größe; die Breite des Stacks ist die des breitesten Kindes.
 * Nutzung/Verankerung: siehe {@link Stack}.
 */
public final class VStack extends Stack {

    public enum Align { LEFT, CENTER, RIGHT }

    private Align align = Align.CENTER;

    public VStack(float gap, GuiComponent... children) {
        super(gap, children);
    }

    public VStack align(Align align) {
        this.align = align;
        return this;
    }

    @Override
    public float width() {
        float max = 0;
        for (GuiComponent c : this.children) max = Math.max(max, c.width());
        return max;
    }

    @Override
    public float height() {
        if (this.children.isEmpty()) return 0;
        float sum = 0;
        for (GuiComponent c : this.children) sum += c.height();
        return sum + this.gap * (this.children.size() - 1);
    }

    @Override
    public void layoutAt(float x, float y) {
        this.applyOwnBounds(x, y);
        float w = this.width();
        float cy = y;
        for (GuiComponent c : this.children) {
            float cx = switch (this.align) {
                case LEFT -> x;
                case CENTER -> x + (w - c.width()) / 2f;
                case RIGHT -> x + w - c.width();
            };
            c.layoutAt(cx, cy);
            cy += c.height() + this.gap;
        }
    }
}
