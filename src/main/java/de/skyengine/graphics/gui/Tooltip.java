package de.skyengine.graphics.gui;

import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.graphics.gui.text.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Zeichnet einen Tooltip-Kasten am Mauszeiger — MC-Optik (Hintergrund 0xF0100010,
 * Rahmen-Gradient 0x505000FF → 0x5028007F, Padding 3, 1-px-Ecken-Notch, Extra-Gap nach der
 * Titelzeile), an den Bildschirmrändern geklemmt.
 *
 * <p>Öffnet eigene Sprite-/Font-Pässe, muss also GANZ am Ende eines Frames laufen, wenn alle
 * anderen Pässe geschlossen sind — der {@link GuiManager} macht das nach {@code screen.render()}
 * für Widget-Tooltips, {@code GuiContainer} zusätzlich für Item-Tooltips über den Slot-Icons.
 *
 * <p>Zeilen sind {@link RichText}, einzelne Wörter können also fett/kursiv/farbig sein.
 */
public final class Tooltip {

    private static final float TEXT_SIZE = GuiText.NORMAL;
    private static final float PAD = 3;
    /** Extra-Abstand nach der Titelzeile (MC-Gap). */
    private static final float TITLE_GAP = 2;
    /** Ab hier wird an Wortgrenzen umgebrochen (MC nutzt 170). */
    private static final float MAX_WIDTH = 180;

    public static void draw(GuiManager gui, List<RichText> lines, double mouseX, double mouseY) {
        if (lines == null || lines.isEmpty()) return;

        float vW = gui.vWidth(), vH = gui.vHeight();
        lines = wrap(gui, lines, Math.min(MAX_WIDTH, vW - 8));
        float lineStep = gui.font().lineHeight(TEXT_SIZE) + 1;
        float textW = 0;
        for (RichText line : lines) {
            textW = Math.max(textW, gui.font().width(line, TEXT_SIZE));
        }
        float w = textW + PAD * 2;
        float h = lines.size() * lineStep - 1 + PAD * 2 + (lines.size() > 1 ? TITLE_GAP : 0);
        /* Rechts neben dem Cursor, an den Bildschirmrändern geklemmt (wie MC). */
        float x = Math.clamp((float) mouseX + 12, 2, Math.max(2, vW - w - 2));
        float y = Math.clamp((float) mouseY - 12, 2, Math.max(2, vH - h - 2));

        SpriteRenderer sr = gui.sprites();
        sr.begin(vW, vH);
        /* Hintergrund mit 1-px-Ecken-Notch: Mittelteil volle Höhe + schmale Randspalten. */
        sr.drawRect(x + 1, y, w - 2, h, 0.063f, 0f, 0.063f, 0.94f);
        sr.drawRect(x, y + 1, 1, h - 2, 0.063f, 0f, 0.063f, 0.94f);
        sr.drawRect(x + w - 1, y + 1, 1, h - 2, 0.063f, 0f, 0.063f, 0.94f);
        /* Rahmen 1 px INNEN, vertikaler Violett-Gradient (Seiten als zwei Hälften angenähert). */
        float half = (h - 4) / 2f;
        sr.drawRect(x + 1, y + 1, w - 2, 1, 0.31f, 0f, 1f, 0.31f);
        sr.drawRect(x + 1, y + h - 2, w - 2, 1, 0.157f, 0f, 0.5f, 0.31f);
        sr.drawRect(x + 1, y + 2, 1, half, 0.31f, 0f, 1f, 0.31f);
        sr.drawRect(x + 1, y + 2 + half, 1, half, 0.157f, 0f, 0.5f, 0.31f);
        sr.drawRect(x + w - 2, y + 2, 1, half, 0.31f, 0f, 1f, 0.31f);
        sr.drawRect(x + w - 2, y + 2 + half, 1, half, 0.157f, 0f, 0.5f, 0.31f);
        sr.end();

        gui.font().begin(vW, vH);
        float ty = y + PAD;
        for (int i = 0; i < lines.size(); i++) {
            gui.font().drawRich(lines.get(i), x + PAD, ty, TEXT_SIZE, Colors.WHITE, true);
            ty += lineStep + (i == 0 ? TITLE_GAP : 0);
        }
        gui.font().end();
    }

    /**
     * Bricht zu lange Zeilen an Wortgrenzen um. Stil und Farbe laufen über den Umbruch weiter
     * (jedes Teilstück behält den Span, aus dem es stammt); ein Wort, das allein schon breiter
     * als {@code maxWidth} ist, wird hart nach Zeichen getrennt.
     */
    private static List<RichText> wrap(GuiManager gui, List<RichText> lines, float maxWidth) {
        boolean any = false;
        for (RichText line : lines) {
            if (gui.font().width(line, TEXT_SIZE) > maxWidth) {
                any = true;
                break;
            }
        }
        if (!any) return lines; // Normalfall: nichts zu tun, keine Allokation

        List<RichText> out = new ArrayList<>(lines.size() + 2);
        for (RichText line : lines) {
            if (gui.font().width(line, TEXT_SIZE) <= maxWidth) {
                out.add(line);
                continue;
            }
            List<Span> current = new ArrayList<>();
            float used = 0;
            for (Span span : line.spans()) {
                StringBuilder piece = new StringBuilder();
                for (String word : splitWords(span.text())) {
                    float wordWidth = gui.font().getStringWidth(word, TEXT_SIZE, span.style());
                    if (used + wordWidth > maxWidth && (used > 0 || !piece.isEmpty())) {
                        /* Zeile voll: aktuelles Teilstück abschließen und Zeile abgeben. */
                        if (!piece.isEmpty()) {
                            current.add(new Span(piece.toString(), span.style(), span.color()));
                            piece.setLength(0);
                        }
                        out.add(RichText.of(current));
                        current = new ArrayList<>();
                        used = 0;
                        word = word.stripLeading(); // führendes Leerzeichen am Zeilenanfang weg
                        wordWidth = gui.font().getStringWidth(word, TEXT_SIZE, span.style());
                    }
                    piece.append(word);
                    used += wordWidth;
                }
                if (!piece.isEmpty()) {
                    current.add(new Span(piece.toString(), span.style(), span.color()));
                }
            }
            if (!current.isEmpty()) out.add(RichText.of(current));
        }
        return out;
    }

    /**
     * Zerlegt in Wörter, wobei das TRENNENDE Leerzeichen am Wortanfang bleibt („ Wort") —
     * so bleibt der Text beim Zusammensetzen unverändert und der Umbruch kann das führende
     * Leerzeichen einfach abschneiden.
     */
    private static List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == ' ' && text.charAt(i - 1) != ' ') {
                words.add(text.substring(start, i));
                start = i;
            }
        }
        words.add(text.substring(start));
        return words;
    }

    private Tooltip() {}
}
