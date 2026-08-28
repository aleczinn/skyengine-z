package de.skyengine.graphics.gui.widget;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.input.Input;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.texture.Texture;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntPredicate;

/**
 * Einzeiliges Textfeld ({@code widget/text_field.png} als 9-Slice): Fokus per Klick, Zeichen
 * über die Char-Events, blinkender Caret.
 *
 * <p><b>Editieren wie in Minecraft/jedem Editor:</b> Selektion (Anker + Caret) per
 * Shift+Pfeile/Pos1/Ende sowie per Maus (Klick setzt den Caret, Ziehen selektiert),
 * Wortsprünge mit STRG+Pfeil, wortweises Löschen mit STRG+Backspace/Entf, Zwischenablage
 * über STRG+A/C/V/X. STRG+C/X ohne Selektion tun bewusst nichts (wie MC — die Zwischenablage
 * soll nicht versehentlich geleert werden). Ist der Text breiter als das Feld, scrollt es
 * horizontal mit dem Caret mit ({@code viewOffset}).
 *
 * <p>Gehaltene Tasten wiederholen: {@code Input} verwirft {@code GLFW_REPEAT} bewusst
 * („DOWN covers held keys"), deshalb macht das Feld sein eigenes Auto-Repeat über
 * {@link Input#isKeyDown} — nur bei Fokus, nur für Navigation/Löschen, nie für STRG-Kombos.
 *
 * <p>Die Modifier-Bits liefert GLFW nur am Event mit; {@link #keyPressed(int)} bekommt aber
 * nur den Keycode. Statt die Signatur durch alle Aufrufer zu ziehen, fragen wir den
 * Tastenzustand direkt beim {@link Input} ab — der ist für die Dauer des Frames eingefroren.
 *
 * <p>Indizes sind {@code char}-basiert (BMP-Annahme, wie der Font-Atlas), nicht
 * Codepoint-basiert.
 */
public final class TextField extends GuiComponent {

    private static final float TEXT_SIZE = GuiText.NORMAL;
    /** Innenabstand; ohne eigenen Rahmen reicht 1 px, sonst muss der 9-Slice-Rand (3) drunter. */
    private static final float PAD = 4, PAD_BORDERLESS = 1;
    private static final Color4 PLACEHOLDER = new Color4(0.55f, 0.55f, 0.55f, 1f);
    /** Auswahl-Hintergrund (MC-Blau) — liegt im Sprite-Pass, also garantiert hinter dem Text. */
    private static final float SEL_R = 0.13f, SEL_G = 0.24f, SEL_B = 0.62f;
    /** Auto-Repeat: erst nach 250 ms, dann ~30 Wiederholungen/s (MC-Werte). */
    private static final long REPEAT_DELAY_MS = 250, REPEAT_RATE_MS = 33;

    private final StringBuilder text = new StringBuilder();
    private final int maxLength;
    private final IntPredicate filter; // null = alle druckbaren Zeichen
    private String placeholder = "";
    /** true: kein eigener Hintergrund — das Feld liegt über einem bereits gemalten Kasten. */
    private boolean borderless;
    private boolean clearOnRightClick;
    private int caret;
    /** Selektions-Anker: Auswahl ist {@code [min(anchor,caret), max(anchor,caret))}. */
    private int anchor;
    /** Horizontaler Sicht-Offset in Text-Pixeln (nur > 0, wenn der Text breiter ist als das Feld). */
    private float viewOffset;
    /** Aktuell wiederholte Taste (0 = keine) + Zeitpunkt der nächsten Wiederholung. */
    private int repeatKey;
    private long repeatNextMs;

    public TextField(float w, float h, int maxLength, IntPredicate filter) {
        this.w = w;
        this.h = h;
        this.maxLength = maxLength;
        this.filter = filter;
    }

    public TextField placeholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    /**
     * Zeichnet ohne eigenen Rahmen — für Felder, die über einem schon in die Textur gemalten
     * Kasten liegen (Creative-Suche). Editieren, Caret und Selektion bleiben unverändert; nur
     * der 9-Slice entfällt und der Innenabstand schrumpft auf {@link #PAD_BORDERLESS}.
     */
    public TextField borderless() {
        this.borderless = true;
        return this;
    }

    /** Leert dieses Feld bei einem Rechtsklick. */
    public TextField clearOnRightClick() {
        this.clearOnRightClick = true;
        return this;
    }

    /** Innenabstand des aktuellen Modus (der 9-Slice-Rand fehlt im randlosen Fall). */
    private float pad() {
        return this.borderless ? PAD_BORDERLESS : PAD;
    }

    public TextField text(String value) {
        this.text.setLength(0);
        this.text.append(value);
        this.caret = this.text.length();
        this.anchor = this.caret;
        return this;
    }

    public String getText() {
        return this.text.toString();
    }

    public void clear() {
        this.text.setLength(0);
        this.caret = 0;
        this.anchor = 0;
        this.viewOffset = 0;
        this.repeatKey = 0;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) this.repeatKey = 0;
    }

    /* --- Selektion / Textänderung (einzige Stelle, die den Text mutiert) --- */

    private int selStart() {
        return Math.min(this.anchor, this.caret);
    }

    private int selEnd() {
        return Math.max(this.anchor, this.caret);
    }

    private boolean hasSelection() {
        return this.anchor != this.caret;
    }

    /** Setzt den Caret; ohne {@code keepSelection} wandert der Anker mit (Auswahl leer). */
    private void moveCaret(int pos, boolean keepSelection) {
        this.caret = Math.clamp(pos, 0, this.text.length());
        if (!keepSelection) this.anchor = this.caret;
    }

    /**
     * Ersetzt die Auswahl durch {@code insert} (leer = nur löschen). Filtert Steuerzeichen und
     * respektiert {@code filter} sowie {@code maxLength} — genutzt von Tippen, Einfügen und Löschen.
     */
    private void replaceSelection(String insert) {
        int start = this.selStart(), end = this.selEnd();
        if (end > start) this.text.delete(start, end);
        this.caret = start;
        this.anchor = start;
        if (insert == null || insert.isEmpty()) return;

        StringBuilder allowed = new StringBuilder();
        for (int i = 0; i < insert.length(); i++) {
            char c = insert.charAt(i);
            if (c < ' ' || c == 127) continue; // Zeilenumbrüche/Steuerzeichen verwerfen
            if (this.filter != null && !this.filter.test(c)) continue;
            if (this.text.length() + allowed.length() >= this.maxLength) break;
            allowed.append(c);
        }
        this.text.insert(start, allowed);
        this.caret = start + allowed.length();
        this.anchor = this.caret;
    }

    /** Wortanfang links von {@code from} (Trenner: alles außer Buchstaben/Ziffern). */
    private int wordStart(int from) {
        int i = from;
        while (i > 0 && !Character.isLetterOrDigit(this.text.charAt(i - 1))) i--;
        while (i > 0 && Character.isLetterOrDigit(this.text.charAt(i - 1))) i--;
        return i;
    }

    /** Wortende rechts von {@code from}. */
    private int wordEnd(int from) {
        int i = from, len = this.text.length();
        while (i < len && !Character.isLetterOrDigit(this.text.charAt(i))) i++;
        while (i < len && Character.isLetterOrDigit(this.text.charAt(i))) i++;
        return i;
    }

    /* --- Eingabe --- */

    private static Input input() {
        return SkyEngine.get().getInput();
    }

    private static boolean ctrlDown() {
        Input in = input();
        return in.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || in.isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private static boolean shiftDown() {
        Input in = input();
        return in.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || in.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    public boolean charTyped(int codepoint) {
        if (!this.focused) return false;
        this.replaceSelection(new String(Character.toChars(codepoint)));
        return true;
    }

    @Override
    public boolean keyPressed(int key) {
        if (!this.focused) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) return false; // ESC bewusst durchreichen (GuiScreen: zurück)

        boolean ctrl = ctrlDown(), shift = shiftDown();

        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> {
                    this.anchor = 0;
                    this.caret = this.text.length();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    if (this.hasSelection()) input().setClipboard(this.selectedText());
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (this.hasSelection()) {
                        input().setClipboard(this.selectedText());
                        this.replaceSelection("");
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    this.replaceSelection(input().getClipboard());
                    return true;
                }
                default -> { /* andere STRG-Kombos unten normal behandeln */ }
            }
        }

        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (this.hasSelection()) {
                    this.replaceSelection("");
                } else if (this.caret > 0) {
                    int from = ctrl ? this.wordStart(this.caret) : this.caret - 1;
                    this.text.delete(from, this.caret);
                    this.caret = from;
                    this.anchor = from;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (this.hasSelection()) {
                    this.replaceSelection("");
                } else if (this.caret < this.text.length()) {
                    this.text.delete(this.caret, ctrl ? this.wordEnd(this.caret) : this.caret + 1);
                    this.anchor = this.caret;
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (ctrl) this.moveCaret(this.wordStart(this.caret), shift);
                else if (!shift && this.hasSelection()) this.moveCaret(this.selStart(), false);
                else this.moveCaret(this.caret - 1, shift);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (ctrl) this.moveCaret(this.wordEnd(this.caret), shift);
                else if (!shift && this.hasSelection()) this.moveCaret(this.selEnd(), false);
                else this.moveCaret(this.caret + 1, shift);
            }
            case GLFW.GLFW_KEY_HOME -> this.moveCaret(0, shift);
            case GLFW.GLFW_KEY_END -> this.moveCaret(this.text.length(), shift);
            default -> {
                /* Alle übrigen Tasten schlucken, solange fokussiert — sonst wirken
                   Buchstaben gleichzeitig als Hotkeys/Schließ-Tasten. */
                return true;
            }
        }
        this.armRepeat(key);
        return true;
    }

    private String selectedText() {
        return this.text.substring(this.selStart(), this.selEnd());
    }

    /** Startet das Auto-Repeat für die gerade gedrückte Navigations-/Lösch-Taste. */
    private void armRepeat(int key) {
        if (this.repeatKey == key) return; // läuft bereits (Wiederholung selbst kommt hier durch)
        this.repeatKey = key;
        this.repeatNextMs = System.currentTimeMillis() + REPEAT_DELAY_MS;
    }

    /** Pro Frame: gehaltene Taste erneut auslösen (max. ein Schritt je Frame). */
    private void tickRepeat() {
        if (this.repeatKey == 0) return;
        if (!this.focused || !input().isKeyDown(this.repeatKey)) {
            this.repeatKey = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < this.repeatNextMs) return;
        this.keyPressed(this.repeatKey);
        this.repeatNextMs = now + REPEAT_RATE_MS;
    }

    /* --- Maus --- */

    @Override
    public boolean mousePressed(double mx, double my, int button) {
        /* Fokus setzt der GuiScreen (über isFocusable) — hier nur konsumieren, wenn getroffen. */
        if (!this.enabled || !this.isMouseOver(mx, my)) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && this.clearOnRightClick) {
            this.clear();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        this.caret = this.caretAt(mx);
        this.anchor = this.caret;
        this.repeatKey = 0;
        return true;
    }

    @Override
    public void mouseDragged(double mx, double my, int button) {
        /* Ziehen erweitert die Auswahl (Anker bleibt stehen). Nur bei Fokus — Drags gehen an
           ALLE Leaves, nicht nur an das angeklickte Widget. */
        if (!this.focused || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        this.caret = this.caretAt(mx);
    }

    /**
     * Caret-Index an der Maus-x-Position. Die Schrift hat kein Kerning
     * ({@code FontRenderer.getStringWidth} summiert nur Advances), Prefix-Breiten sind also
     * exakt additiv und damit verlustfrei umkehrbar. Rastet auf die nähere Zeichenkante.
     */
    private int caretAt(double mx) {
        var font = SkyEngine.get().getGame().getGuiManager().font();
        float local = (float) (mx - (this.x + this.pad())) + this.viewOffset;
        if (local <= 0) return 0;
        float acc = 0;
        for (int i = 0; i < this.text.length(); i++) {
            float cw = font.getStringWidth(String.valueOf(this.text.charAt(i)), TEXT_SIZE);
            if (local < acc + cw / 2f) return i;
            acc += cw;
        }
        return this.text.length();
    }

    /* --- Rendering --- */

    private float innerWidth() {
        return this.w - 2 * this.pad();
    }

    private float prefixWidth(GuiManager gui, int index) {
        return gui.font().getStringWidth(this.text.substring(0, index), TEXT_SIZE);
    }

    /** Hält den Caret im sichtbaren Bereich (MC-Verhalten bei zu langem Text). */
    private void clampView(GuiManager gui) {
        float inner = this.innerWidth();
        float total = gui.font().getStringWidth(this.text.toString(), TEXT_SIZE);
        if (total <= inner) {
            this.viewOffset = 0;
            return;
        }
        float caretX = this.prefixWidth(gui, this.caret);
        if (caretX - this.viewOffset > inner) this.viewOffset = caretX - inner;
        if (caretX - this.viewOffset < 0) this.viewOffset = caretX;
        this.viewOffset = Math.clamp(this.viewOffset, 0f, total - inner);
    }

    @Override
    public void renderBackground(GuiManager gui, double mx, double my) {
        this.tickRepeat();
        this.clampView(gui);

        /* Im randlosen Modus entfällt NUR der 9-Slice — Caret und Selektion unten müssen
           weiterlaufen, und tickRepeat/clampView haben hier ihren einzigen Aufrufpunkt. */
        if (!this.borderless) {
            Texture tex = (this.focused || this.hovered)
                    ? gui.textures().textFieldHighlighted : gui.textures().textField;
            gui.sprites().drawNineSlice(tex, this.x, this.y, this.w, this.h, 3);
        }

        float pad = this.pad();
        /* Auswahl/Caret sitzen am Textband, damit sie ohne Rahmen nicht am Feldrand kleben. */
        float bandY = this.textTop(gui);
        float bandH = gui.font().lineHeight(TEXT_SIZE);

        /* Auswahl-Box (Sprite-Pass = hinter dem Text), auf das Feldinnere geklammert. */
        if (this.focused && this.hasSelection()) {
            float left = Math.max(this.x + pad + this.prefixWidth(gui, this.selStart()) - this.viewOffset,
                    this.x + pad);
            float right = Math.min(this.x + pad + this.prefixWidth(gui, this.selEnd()) - this.viewOffset,
                    this.x + this.w - pad);
            if (right > left) {
                gui.sprites().drawRect(left, bandY, right - left, bandH, SEL_R, SEL_G, SEL_B, 1f);
            }
        }

        /* Caret als 1-px-Rect im Sprite-Pass (zeitbasiertes Blinken, kein Tick nötig). */
        if (this.focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = this.x + pad + this.prefixWidth(gui, this.caret) - this.viewOffset;
            if (cx >= this.x + pad && cx <= this.x + this.w - pad) {
                gui.sprites().drawRect(cx, bandY + 1, 1, bandH - 2, 1f, 1f, 1f, 1f);
            }
        }
    }

    /** Obere Kante des Textbands (vertikal im Feld zentriert). */
    private float textTop(GuiManager gui) {
        return this.y + (this.h - gui.font().lineHeight(TEXT_SIZE)) / 2f;
    }

    @Override
    public void renderText(GuiManager gui, double mx, double my) {
        float pad = this.pad();
        float ty = this.textTop(gui);
        if (this.text.isEmpty() && !this.focused) {
            gui.font().drawString(this.placeholder, this.x + pad, ty, TEXT_SIZE, PLACEHOLDER);
            return;
        }
        String value = this.text.toString();
        float tx = this.x + pad - this.viewOffset;
        if (gui.font().getStringWidth(value, TEXT_SIZE) <= this.innerWidth()) {
            gui.font().drawStringWithShadow(value, tx, ty, TEXT_SIZE, Colors.WHITE);
            return;
        }
        /* Zu langer Text: eigenes begin/end-Paar INNERHALB des Scissors, weil der FontRenderer
           erst bei end() flusht (sonst würde das Clipping den ganzen Screen-Text treffen bzw.
           gar nicht wirken). Danach den Screen-Pass wieder öffnen, wie er vorgefunden wurde.
           Der Zuschnitt richtet sich nach dem Innenabstand: die frühere feste 2-px-Einrückung
           ließ von einem 12 px hohen Feld nur 8 px übrig und hätte den Text beschnitten. */
        gui.font().end();
        gui.enableScissor(this.x + pad, this.y, this.w - 2 * pad, this.h);
        gui.font().begin(gui.vWidth(), gui.vHeight());
        gui.font().drawStringWithShadow(value, tx, ty, TEXT_SIZE, Colors.WHITE);
        gui.font().end();
        gui.disableScissor();
        gui.font().begin(gui.vWidth(), gui.vHeight());
    }
}
