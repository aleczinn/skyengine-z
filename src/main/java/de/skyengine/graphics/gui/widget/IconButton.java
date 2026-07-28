package de.skyengine.graphics.gui.widget;

import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.texture.Texture;

/**
 * Quadratischer Button mit Sprite statt Text (z.B. „Welt importieren"). Erbt bewusst von
 * {@link Button}: Hintergrund-9-Slice, Klick-Logik und der zentrale Klick-Sound
 * ({@code GuiScreen.mousePressed} prüft {@code instanceof Button}) kommen dadurch gratis.
 *
 * <p>Beschriftung gibt es keine — was er tut, sagt der Tooltip ({@code .tooltip("…")}).
 *
 * <p><b>Zuschnitt (wichtig):</b> Die MC-Sprites füllen ihre Bildfläche oft nicht aus —
 * {@code icon/new_realm.png} ist 40×20, der Globus darin sitzt in einem 16×16-Fenster. Ohne
 * {@link #uv} würde die ganze Fläche eingepasst und das Motiv wäre winzig und verschoben.
 * Der Ausschnitt wird seitenverhältnis-korrekt eingepasst und mittig gezeichnet.
 */
public final class IconButton extends Button {

    /** Rand zwischen Button-Kante und Icon. */
    private static final float PAD = 2;

    private final Texture icon;
    private final Texture iconHighlighted;
    /* Pixel-Ausschnitt der Quelltextur; -1 = ganze Textur. */
    private int srcX = -1, srcY, srcW, srcH;

    public IconButton(Texture icon, Texture iconHighlighted, float size, Runnable onPress) {
        super("", size, size, onPress);
        this.icon = icon;
        this.iconHighlighted = iconHighlighted;
    }

    /** Nur diesen Pixel-Ausschnitt der Textur zeichnen (Motiv-Zuschnitt). Chainbar. */
    public IconButton uv(int x, int y, int w, int h) {
        this.srcX = x;
        this.srcY = y;
        this.srcW = w;
        this.srcH = h;
        return this;
    }

    @Override
    public void renderBackground(GuiManager gui, double mx, double my) {
        super.renderBackground(gui, mx, my);
        Texture tex = this.hovered && this.iconHighlighted != null ? this.iconHighlighted : this.icon;
        if (tex == null) return;

        float texW = tex.getWidth(), texH = tex.getHeight();
        float sx = this.srcX >= 0 ? this.srcX : 0;
        float sy = this.srcX >= 0 ? this.srcY : 0;
        float sw = this.srcX >= 0 ? this.srcW : texW;
        float sh = this.srcX >= 0 ? this.srcH : texH;

        /* Seitenverhältnis erhalten und in das Innere des Buttons einpassen. */
        float inner = Math.min(this.w, this.h) - 2 * PAD;
        float scale = Math.min(inner / sw, inner / sh);
        float dw = sw * scale, dh = sh * scale;
        gui.sprites().drawSprite(tex, this.x + (this.w - dw) / 2f, this.y + (this.h - dh) / 2f,
                dw, dh, sx / texW, sy / texH, (sx + sw) / texW, (sy + sh) / texH);
    }

    @Override
    public void renderText(GuiManager gui, double mx, double my) {
        /* Kein Label. */
    }
}
