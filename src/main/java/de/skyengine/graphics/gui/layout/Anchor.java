package de.skyengine.graphics.gui.layout;

/**
 * Anker-Position im virtuellen GUI-Raum: löst für ein Element der Größe w×h die
 * Zielposition relativ zu vW×vH auf (padX/padY = Abstand zum jeweiligen Rand).
 */
public enum Anchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

    public float resolveX(float vW, float w, float padX) {
        return switch (this) {
            case TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> padX;
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> (vW - w) / 2f;
            case TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> vW - w - padX;
        };
    }

    public float resolveY(float vH, float h, float padY) {
        return switch (this) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> padY;
            case CENTER_LEFT, CENTER, CENTER_RIGHT -> (vH - h) / 2f;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> vH - h - padY;
        };
    }
}
