package de.skyengine.graphics.gui;

/**
 * Scrollbalken einer Scroll-Liste (Tastenbelegung/Weltliste): zeichnet Track + Thumb und
 * übersetzt Klick/Drag in einen neuen Scroll-Offset. Bewusst KEIN GuiComponent — die
 * Scroll-Listen laufen außerhalb der leaves-Mechanik des GuiScreen. Optik wie MCs
 * Listen-Scrollbalken (Rechtecke: dunkler Track, grauer Thumb).
 */
public final class ScrollBar {

    public static final float WIDTH = 6;
    private static final float MIN_THUMB_H = 10;

    private float x, y, viewH;
    private boolean dragging;
    /** Abstand Mauszeiger → Thumb-Oberkante beim Anfassen (verhindert Springen). */
    private double grabOffset;

    /** Position/Sichthöhe setzen (im init() des Screens, rechts neben der Liste). */
    public void layout(float x, float y, float viewH) {
        this.x = x;
        this.y = y;
        this.viewH = viewH;
    }

    /** Sichtbar nur, wenn der Inhalt höher als der Viewport ist. */
    public boolean isVisible(float contentH) {
        return contentH > this.viewH;
    }

    /** Im Sprite-Pass zeichnen (außerhalb des Listen-Scissors). */
    public void draw(GuiManager gui, float contentH, double offset) {
        if (!this.isVisible(contentH)) return;
        gui.sprites().drawRect(this.x, this.y, WIDTH, this.viewH, 0f, 0f, 0f, 0.6f);
        float th = this.thumbH(contentH);
        float ty = this.y + (float) (offset / this.maxScroll(contentH) * (this.viewH - th));
        gui.sprites().drawRect(this.x, ty, WIDTH, th, 0.55f, 0.55f, 0.55f, 1f);
        gui.sprites().drawRect(this.x, ty, WIDTH - 1, th - 1, 0.75f, 0.75f, 0.75f, 1f);
    }

    /**
     * Klick auf den Balken: Thumb anfassen (Drag startet) oder Track-Klick (Thumb springt
     * zur Mausposition). Liefert den neuen Offset, sonst −1 (nicht getroffen).
     */
    public double mousePressed(double mx, double my, float contentH, double offset) {
        if (!this.isVisible(contentH)
                || mx < this.x || mx >= this.x + WIDTH || my < this.y || my >= this.y + this.viewH) {
            return -1;
        }
        float th = this.thumbH(contentH);
        double ty = this.y + offset / this.maxScroll(contentH) * (this.viewH - th);
        this.dragging = true;
        if (my >= ty && my < ty + th) {
            this.grabOffset = my - ty;
            return offset;
        }
        this.grabOffset = th / 2.0;
        return this.offsetForThumbY(my - this.grabOffset, contentH);
    }

    /** Laufender Drag: neuer Offset aus der Mausposition, sonst −1 (kein Drag aktiv). */
    public double mouseDragged(double my, float contentH) {
        if (!this.dragging) return -1;
        return this.offsetForThumbY(my - this.grabOffset, contentH);
    }

    public void mouseReleased() {
        this.dragging = false;
    }

    private float thumbH(float contentH) {
        return Math.max(MIN_THUMB_H, this.viewH * this.viewH / contentH);
    }

    private float maxScroll(float contentH) {
        return contentH - this.viewH;
    }

    private double offsetForThumbY(double thumbY, float contentH) {
        float th = this.thumbH(contentH);
        double t = (thumbY - this.y) / (this.viewH - th);
        return Math.clamp(t, 0.0, 1.0) * this.maxScroll(contentH);
    }
}
