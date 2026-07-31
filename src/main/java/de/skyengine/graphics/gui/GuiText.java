package de.skyengine.graphics.gui;

/**
 * Zentrale Schriftgrößen der GUI — <b>die einzige Stelle, an der an der Textgröße gedreht wird</b>.
 * Werte sind virtuelle Pixel (Zielzeilenhöhe, siehe
 * {@link de.skyengine.graphics.gui.font.FontRenderer}); die GUI-Skalierung kommt separat aus dem
 * {@link GuiManager} obendrauf.
 *
 * <p><b>Der Font ist MONOSPACE:</b> die Breite eines Textes ist exakt
 * {@code 0,5 × Größe × Zeichenzahl}. 20 % größere Schrift kostet also 20 % mehr Platz — und es
 * gibt nirgends ein Clipping. Wer hier hochdreht, muss die fest gesetzten Widget-Breiten
 * (Buttons, Listenspalten, {@code Tooltip.MAX_WIDTH}) mit prüfen, sonst laufen Beschriftungen
 * einfach heraus. Ebenso die Screens mit hartkodierten Textzeilen-Offsets
 * ({@code GuiSelectWorld}, {@code GuiImportWorld}).
 */
public final class GuiText {

    /** Nebentext: Untertitel in Listen, Log-Ausgaben, Versions-/Copyright-Zeile. */
    public static final float SMALL = 10;

    /** Fließtext: Buttons, Slider, Labels, Textfelder, Tooltips, HUD, Fenstertitel. */
    public static final float NORMAL = 12;

    /** Screen-Titel. */
    public static final float TITLE = 16;

    /** Großer Titel: Todesbildschirm, Boot-Screen. */
    public static final float LARGE = 22;

    /** Hauptmenü-Schriftzug, wenn kein Logo vorliegt. */
    public static final float HERO = 32;

    private GuiText() {}
}
