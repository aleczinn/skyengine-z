package de.skyengine.graphics.gui.text;

import de.skyengine.graphics.gui.font.FontRenderer;
import de.skyengine.graphics.gui.font.FontStyle;

import java.util.ArrayList;
import java.util.List;

/** Gemeinsamer, stiltreuer Wortumbruch fuer Chat und Tooltips. */
public final class RichTextWrapper {

    @FunctionalInterface
    public interface Measurer { float width(String text, FontStyle style); }

    /** sourceSpanIndices ordnet jeden ausgegebenen Span seinem Span im Ursprungstext zu. */
    public record Line(RichText text, List<Integer> sourceSpanIndices) {}

    public static List<Line> wrap(RichText text, FontRenderer font, float size, float maxWidth) {
        return wrap(text, maxWidth, (value, style) -> font.getStringWidth(value, size, style));
    }

    /** Headless testbare Variante; der Aufrufer liefert nur die Glyphenbreiten. */
    public static List<Line> wrap(RichText text, float maxWidth, Measurer measurer) {
        if (text == null || text.isEmpty()) return List.of(new Line(RichText.EMPTY, List.of()));
        float totalWidth = 0F;
        for (Span span : text.spans()) totalWidth += measurer.width(span.text(), span.style());
        if (totalWidth <= maxWidth && text.spans().stream().noneMatch(s -> s.text().indexOf('\n') >= 0)) {
            List<Integer> sources = new ArrayList<>(text.spans().size());
            for (int i = 0; i < text.spans().size(); i++) sources.add(i);
            return List.of(new Line(text, List.copyOf(sources)));
        }

        List<Line> output = new ArrayList<>();
        Builder line = new Builder();
        float used = 0F;
        for (int sourceIndex = 0; sourceIndex < text.spans().size(); sourceIndex++) {
            Span span = text.spans().get(sourceIndex);
            String value = span.text();
            int offset = 0;
            while (offset < value.length()) {
                if (value.charAt(offset) == '\n') {
                    output.add(line.build());
                    line = new Builder();
                    used = 0F;
                    offset++;
                    continue;
                }
                boolean whitespace = Character.isWhitespace(value.charAt(offset));
                int end = offset + 1;
                while (end < value.length() && value.charAt(end) != '\n'
                        && Character.isWhitespace(value.charAt(end)) == whitespace) end++;
                String token = value.substring(offset, end);
                offset = end;

                if (whitespace && line.isEmpty()) continue;
                float tokenWidth = measurer.width(token, span.style());
                if (used + tokenWidth <= maxWidth) {
                    line.append(token, span, sourceIndex);
                    used += tokenWidth;
                    continue;
                }
                if (whitespace) {
                    output.add(line.build());
                    line = new Builder();
                    used = 0F;
                    continue;
                }
                if (!line.isEmpty()) {
                    output.add(line.build());
                    line = new Builder();
                    used = 0F;
                }
                if (tokenWidth <= maxWidth) {
                    line.append(token, span, sourceIndex);
                    used = tokenWidth;
                    continue;
                }

                // Nur ein einzelnes ueberbreites Token wird als lesbarer Fallback hart getrennt.
                int start = 0;
                while (start < token.length()) {
                    int split = start;
                    float partWidth = 0F;
                    while (split < token.length()) {
                        int next = split + Character.charCount(token.codePointAt(split));
                        float candidate = measurer.width(token.substring(start, next), span.style());
                        if (candidate > maxWidth && split > start) break;
                        partWidth = candidate;
                        split = next;
                        if (candidate > maxWidth) break;
                    }
                    line.append(token.substring(start, split), span, sourceIndex);
                    used = partWidth;
                    start = split;
                    if (start < token.length()) {
                        output.add(line.build());
                        line = new Builder();
                        used = 0F;
                    }
                }
            }
        }
        if (!line.isEmpty() || output.isEmpty()) output.add(line.build());
        return List.copyOf(output);
    }

    private static final class Builder {
        private final List<Span> spans = new ArrayList<>();
        private final List<Integer> sources = new ArrayList<>();
        boolean isEmpty() { return spans.isEmpty(); }
        void append(String text, Span style, int source) {
            if (text.isEmpty()) return;
            int last = spans.size() - 1;
            if (last >= 0 && sources.get(last) == source) {
                Span previous = spans.get(last);
                spans.set(last, new Span(previous.text() + text, previous.style(), previous.color()));
            } else {
                spans.add(new Span(text, style.style(), style.color()));
                sources.add(source);
            }
        }
        Line build() { return new Line(RichText.of(spans), List.copyOf(sources)); }
    }

    private RichTextWrapper() {}
}
