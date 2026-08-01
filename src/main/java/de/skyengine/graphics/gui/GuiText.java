package de.skyengine.graphics.gui;

/**
 * Zentrale Schriftgrößen der GUI — <b>die einzige Stelle, an der an der Textgröße gedreht wird</b>.
 * Werte sind virtuelle Pixel (Zielzeilenhöhe, siehe
 * {@link de.skyengine.graphics.gui.font.FontRenderer}); die GUI-Skalierung kommt separat aus dem
 * {@link GuiManager} obendrauf.
 *
 * <p><b>Der Font ist MONOSPACE:</b> die Breite eines Textes ist
 * {@code 0,5 × Größe × Zeichenzahl}. Seit {@code FontRenderer.SPACE_ADVANCE} das Leerzeichen
 * schmaler zeichnet, ist das eine <b>Obergrenze</b> statt einer exakten Formel — Texte mit
 * Leerzeichen fallen etwas kürzer aus, Abschätzungen für feste Widget-Breiten bleiben damit
 * gültig (nur konservativer). 20 % größere Schrift kostet 20 % mehr Platz — und es
 * gibt nirgends ein Clipping. Wer hier hochdreht, muss die fest gesetzten Widget-Breiten
 * (Buttons, Listenspalten, {@code Tooltip.MAX_WIDTH}) mit prüfen, sonst laufen Beschriftungen
 * einfach heraus. Ebenso die Screens mit hartkodierten Textzeilen-Offsets
 * ({@code GuiSelectWorld}, {@code GuiImportWorld}).
 */
public final class GuiText {

    /** Dichte Nebenzeilen: Untertitel der MC-Import-Liste. */
    public static final float TINY = 7;

    /** Nebentext: Tooltips, Listen-Untertitel, Hinweise, Versions-/Copyright-Zeile. */
    public static final float SMALL = 8;

    /** Formular-Beschriftungen und Listen-Einträge. */
    public static final float COMPACT = 9;

    /** Fließtext: Buttons, Slider, Textfelder, HUD, Debug-Overlay. */
    public static final float NORMAL = 10;

    /** Hervorgehoben: Stapelzahlen im Slot, Ladehinweis. */
    public static final float MEDIUM = 12;

    /** Screen-Titel. */
    public static final float TITLE = 14;

    /** Großer Titel: Todesbildschirm, Boot-Screen. */
    public static final float LARGE = 20;

    /** Hauptmenü-Schriftzug, wenn kein Logo vorliegt. */
    public static final float HERO = 32;

    private GuiText() {}
}
