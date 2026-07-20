package de.skyengine.graphics.gui.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontaler Stapel: positioniert Kinder nebeneinander mit festem Abstand ({@code gap}).
 * Die Höhe des Stacks ist die des höchsten Kindes; Kinder werden vertikal darin ausgerichtet.
 */
public final class HStack implements Layoutable {

    public enum Align { TOP, CENTER, BOTTOM }

    private final List<Layoutable> children = new ArrayList<>();
    private final float gap;
    private Align align = Align.CENTER;

    public HStack(float gap) {
        this.gap = gap;
    }

    public HStack align(Align align) {
        this.align = align;
        return this;
    }

    public HStack add(Layoutable child) {
        this.children.add(child);
        return this;
    }

    @Override
    public float width() {
        if (this.children.isEmpty()) return 0;
        float sum = 0;
        for (Layoutable c : this.children) sum += c.width();
        return sum + this.gap * (this.children.size() - 1);
    }

    @Override
    public float height() {
        float max = 0;
        for (Layoutable c : this.children) max = Math.max(max, c.height());
        return max;
    }

    @Override
    public void layoutAt(float x, float y) {
        float h = this.height();
        float cx = x;
        for (Layoutable c : this.children) {
            float cy = switch (this.align) {
                case TOP -> y;
                case CENTER -> y + (h - c.height()) / 2f;
                case BOTTOM -> y + h - c.height();
            };
            c.layoutAt(cx, cy);
            cx += c.width() + this.gap;
        }
    }

    public void layoutAnchored(float vW, float vH, Anchor anchor, float padX, float padY) {
        this.layoutAt(anchor.resolveX(vW, this.width(), padX), anchor.resolveY(vH, this.height(), padY));
    }
}
