package de.skyengine.graphics.gui.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertikaler Stapel: positioniert Kinder untereinander mit festem Abstand ({@code gap}).
 * Kinder behalten ihre eigene Größe; die Breite des Stacks ist die des breitesten Kindes.
 * Layout passiert nur bei {@link #layoutAt}/{@link #layoutAnchored} — vom Screen in
 * {@code init()} aufzurufen, nicht pro Frame.
 */
public final class VStack implements Layoutable {

    public enum Align { LEFT, CENTER, RIGHT }

    private final List<Layoutable> children = new ArrayList<>();
    private final float gap;
    private Align align = Align.CENTER;

    public VStack(float gap) {
        this.gap = gap;
    }

    public VStack align(Align align) {
        this.align = align;
        return this;
    }

    public VStack add(Layoutable child) {
        this.children.add(child);
        return this;
    }

    @Override
    public float width() {
        float max = 0;
        for (Layoutable c : this.children) max = Math.max(max, c.width());
        return max;
    }

    @Override
    public float height() {
        if (this.children.isEmpty()) return 0;
        float sum = 0;
        for (Layoutable c : this.children) sum += c.height();
        return sum + this.gap * (this.children.size() - 1);
    }

    @Override
    public void layoutAt(float x, float y) {
        float w = this.width();
        float cy = y;
        for (Layoutable c : this.children) {
            float cx = switch (this.align) {
                case LEFT -> x;
                case CENTER -> x + (w - c.width()) / 2f;
                case RIGHT -> x + w - c.width();
            };
            c.layoutAt(cx, cy);
            cy += c.height() + this.gap;
        }
    }

    public void layoutAnchored(float vW, float vH, Anchor anchor, float padX, float padY) {
        this.layoutAt(anchor.resolveX(vW, this.width(), padX), anchor.resolveY(vH, this.height(), padY));
    }
}
