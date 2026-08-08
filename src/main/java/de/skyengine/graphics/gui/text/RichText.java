package de.skyengine.graphics.gui.text;

import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.font.FontStyle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Formatierter Text aus HTML-artigem Markup — für Tooltips und Log-Ausgaben, damit einzelne
 * Wörter fett/kursiv/farbig sein können:
 *
 * <pre>{@code "Ich bin ein Text, welcher <green>performant</> ist."
 * "<b>Achtung:</b> <red>Fehler beim Laden</>"}</pre>
 *
 * <p><b>Tags:</b> {@code <b>} (fett), {@code <i>} (kursiv), ein Farbname aus
 * {@link TextColors} ({@code <red>}, {@code <gold>}, …) oder {@code <#ff5555>} für freie
 * Hex-Farben. Zusätzlich funktionieren Minecrafts Legacy-Codes {@code §0} bis {@code §f},
 * {@code §l} (fett), {@code §o} (kursiv) und {@code §r} (Reset).
 * <b>Ein schließendes Tag nimmt immer das zuletzt geöffnete zurück</b> — der Name
 * darin wird ignoriert, {@code </>}, {@code </b>} und {@code </red>} sind gleichwertig.
 * Stile und Farben stapeln sich ({@code <b>fett <red>und rot</></>}).
 *
 * <p>Alles, was kein bekanntes Tag ist, bleibt <b>literaler Text</b> — ein einzelnes
 * {@code <} im Text ist also harmlos. <b>Zeilenumbruch:</b> {@code <br>} oder {@code \n},
 * ausgewertet von {@link #parseLines(String)}; zu lange Zeilen bricht der
 * {@code Tooltip} zusätzlich automatisch an Wortgrenzen um.
 *
 * <p>Der Parser läuft einmalig beim Erzeugen (im {@code init()} eines Screens bzw. beim
 * Anlegen einer Log-Zeile), nicht pro Frame.
 */
public final class RichText {

    public static final RichText EMPTY = new RichText(List.of());
    /** Zeilen-Trenner im Markup: {@code <br>}, {@code <br/>}, {@code <br />} oder {@code \n}. */
    private static final Pattern LINE_BREAK = Pattern.compile("<br\\s*/?>|\\n");

    private final List<Span> spans;

    private RichText(List<Span> spans) {
        this.spans = spans;
    }

    /** Aus fertigen Spans bauen (Umbruch-Logik im {@code Tooltip}). */
    public static RichText of(List<Span> spans) {
        return spans == null || spans.isEmpty() ? EMPTY : new RichText(List.copyOf(spans));
    }

    public List<Span> spans() {
        return this.spans;
    }

    public boolean isEmpty() {
        return this.spans.isEmpty();
    }

    /** Unformatierter Text (kein Parser-Durchlauf). */
    public static RichText plain(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        return new RichText(List.of(new Span(text, FontStyle.REGULAR, null)));
    }

    /** Unformatierter Text in einer festen Farbe. */
    public static RichText plain(String text, Color4 color) {
        if (text == null || text.isEmpty()) return EMPTY;
        return new RichText(List.of(new Span(text, FontStyle.REGULAR, color)));
    }

    /** Markup in Spans zerlegen. */
    public static RichText parse(String markup) {
        return parse(markup, null);
    }

    /** Markup mit einer Grundfarbe parsen; {@code §r} kehrt zu dieser Farbe zurück. */
    public static RichText parse(String markup, Color4 baseColor) {
        if (markup == null || markup.isEmpty()) return EMPTY;
        if (markup.indexOf('<') < 0 && markup.indexOf('§') < 0) {
            return baseColor == null ? plain(markup) : plain(markup, baseColor);
        }

        List<Span> spans = new ArrayList<>();
        Deque<Style> stack = new ArrayDeque<>();
        Style base = new Style(FontStyle.REGULAR, baseColor);
        Style current = base;
        StringBuilder buffer = new StringBuilder();

        int i = 0;
        while (i < markup.length()) {
            char c = markup.charAt(i);
            if (c == '§' && i + 1 < markup.length()) {
                char code = Character.toLowerCase(markup.charAt(i + 1));
                Color4 color = TextColors.parseLegacy(code);
                if (color != null) {
                    flush(spans, buffer, current);
                    // Wie Minecraft: ein Farbcode verwirft aktive Legacy-Schriftstile.
                    current = new Style(FontStyle.REGULAR, color);
                    i += 2;
                    continue;
                }
                if (code == 'l' || code == 'o' || code == 'r') {
                    flush(spans, buffer, current);
                    if (code == 'r') {
                        current = base;
                        stack.clear();
                    } else {
                        current = new Style(code == 'l' ? FontStyle.BOLD : FontStyle.ITALIC,
                                current.color());
                    }
                    i += 2;
                    continue;
                }
            }
            if (c != '<') {
                buffer.append(c);
                i++;
                continue;
            }
            int end = markup.indexOf('>', i + 1);
            if (end < 0) { // unabgeschlossenes '<' -> Rest ist Text
                buffer.append(markup, i, markup.length());
                break;
            }
            String tag = markup.substring(i + 1, end);
            if (tag.startsWith("/")) {
                flush(spans, buffer, current);
                current = stack.isEmpty() ? base : stack.pop();
                i = end + 1;
                continue;
            }
            Style next = current.with(tag);
            if (next == null) { // unbekanntes Tag -> literal
                buffer.append(markup, i, end + 1);
                i = end + 1;
                continue;
            }
            flush(spans, buffer, current);
            stack.push(current);
            current = next;
            i = end + 1;
        }
        flush(spans, buffer, current);
        return spans.isEmpty() ? EMPTY : new RichText(List.copyOf(spans));
    }

    /**
     * Markup zeilenweise parsen — Trenner sind {@code <br>} und {@code \n}. {@code <br>} wird
     * bewusst HIER behandelt (und nicht im Span-Parser), damit es ein Zeilen-Trenner bleibt
     * und nicht als unbekanntes Tag literal im Text landet.
     */
    public static List<RichText> parseLines(String markup) {
        return parseLines(markup, null);
    }

    /** Zeilenweises Markup mit einer Grundfarbe für alle Zeilen. */
    public static List<RichText> parseLines(String markup, Color4 baseColor) {
        if (markup == null || markup.isEmpty()) return List.of();
        String[] parts = LINE_BREAK.split(markup, -1);
        if (parts.length == 1) return List.of(parse(markup, baseColor));
        List<RichText> lines = new ArrayList<>(parts.length);
        for (String line : parts) {
            lines.add(parse(line, baseColor));
        }
        return lines;
    }

    /** Markup entfernen — für Ausgaben ohne Formatierung (Konsole/Logdatei). */
    public static String strip(String markup) {
        if (markup == null || (markup.indexOf('<') < 0 && markup.indexOf('§') < 0)) return markup;
        StringBuilder out = new StringBuilder(markup.length());
        for (Span span : parse(LINE_BREAK.matcher(markup).replaceAll("\n")).spans()) {
            out.append(span.text());
        }
        return out.toString();
    }

    private static void flush(List<Span> spans, StringBuilder buffer, Style style) {
        if (buffer.isEmpty()) return;
        spans.add(new Span(buffer.toString(), style.font(), style.color()));
        buffer.setLength(0);
    }

    /** Aktueller Stil-Zustand des Parsers (unveränderlich, damit der Stack einfach bleibt). */
    private record Style(FontStyle font, Color4 color) {

        /** Neuer Zustand für ein öffnendes Tag; null, wenn das Tag unbekannt ist. */
        Style with(String tag) {
            switch (tag) {
                case "b", "bold" -> {
                    return new Style(FontStyle.BOLD, this.color);
                }
                case "i", "italic" -> {
                    return new Style(FontStyle.ITALIC, this.color);
                }
                default -> {
                    Color4 parsed = TextColors.parse(tag);
                    return parsed != null ? new Style(this.font, parsed) : null;
                }
            }
        }
    }
}
