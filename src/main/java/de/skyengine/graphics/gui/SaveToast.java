package de.skyengine.graphics.gui;

import de.skyengine.core.i18n.I18n;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.font.FontRenderer;

/**
 * Kurze „Spiel gespeichert"-Meldung unten rechts, die nach kurzer Standzeit ausblendet.
 * Gehalten vom GameContainer und gezeichnet NACH dem regulären GUI-Pass (wie das
 * {@link DebugOverlay}) — nur so liegt sie über dem abgedunkelten Pausenmenü, wo sie am
 * ehesten gebraucht wird; im HUD läge sie unter dessen Dim.
 *
 * <p>Die Zeitbasis ist bewusst die Wanduhr und nicht der partialTick oder ein Tick-Zähler:
 * bei offenem Pausenmenü wird der partialTick eingefroren (GameContainer.updatePaused) und
 * die Welt tickt nicht mehr — die Meldung bliebe sonst stehen, statt auszublenden.
 */
public final class SaveToast {

    private static final float TEXT_SIZE = 8.0F;
    private static final float MARGIN = 4.0F;
    /** Volle Deckkraft, danach lineares Ausblenden (gleiche Kurve wie der Hotbar-Itemname). */
    private static final long HOLD_MS = 2000, FADE_MS = 500;

    private long shownAt;

    /** Blendet die Meldung ein (erneutes Auslösen startet die Standzeit neu). */
    public void show() {
        this.shownAt = System.currentTimeMillis();
    }

    public void render(GuiManager gui) {
        float alpha = this.alpha();
        if (alpha <= 0) return;
        FontRenderer font = gui.font();
        /* Live-Sprachwechsel: erst beim Zeichnen übersetzen, nicht bei show(). */
        String text = I18n.tr("gui.save.saved");
        float vW = gui.vWidth(), vH = gui.vHeight();
        font.begin(vW, vH);
        font.drawStringWithShadow(text, vW - font.getStringWidth(text, TEXT_SIZE) - MARGIN,
                vH - font.lineHeight(TEXT_SIZE) - MARGIN, TEXT_SIZE, new Color4(0.6f, 0.6f, 0.6f, alpha));
        font.end();
    }

    private float alpha() {
        if (this.shownAt == 0) return 0f;
        long since = System.currentTimeMillis() - this.shownAt;
        if (since >= HOLD_MS + FADE_MS) return 0f;
        if (since <= HOLD_MS) return 1f;
        return (HOLD_MS + FADE_MS - since) / (float) FADE_MS;
    }
}
