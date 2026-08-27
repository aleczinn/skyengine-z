package de.skyengine.graphics.gui.text;

import de.skyengine.graphics.gui.font.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RichTextWrapperTest {
    private static final RichTextWrapper.Measurer MONOSPACE = (text, style) -> text.codePointCount(0, text.length());

    @Test
    void wrapsAtWordsAndHardSplitsOnlyOversizedToken() {
        List<RichTextWrapper.Line> words = RichTextWrapper.wrap(
                RichText.plain("Hallo schöne Welt"), 12, MONOSPACE);
        assertEquals(List.of("Hallo schöne", "Welt"), words.stream().map(line -> plain(line.text())).toList());

        List<RichTextWrapper.Line> token = RichTextWrapper.wrap(
                RichText.plain("minecraft:very_long_block_state"), 10, MONOSPACE);
        assertEquals(List.of("minecraft:", "very_long_", "block_stat", "e"),
                token.stream().map(line -> plain(line.text())).toList());
    }

    @Test
    void preservesSourceSpanMappingAcrossLines() {
        RichText rich = RichText.of(List.of(
                new Span("first ", FontStyle.REGULAR, null),
                new Span("clickable-value", FontStyle.BOLD, null)));
        List<RichTextWrapper.Line> lines = RichTextWrapper.wrap(rich, 8, MONOSPACE);
        assertEquals(3, lines.size());
        assertEquals(List.of(0), lines.getFirst().sourceSpanIndices());
        assertEquals(List.of(1), lines.get(1).sourceSpanIndices());
        assertEquals(List.of(1), lines.getLast().sourceSpanIndices());
    }

    private static String plain(RichText text) {
        StringBuilder result = new StringBuilder();
        for (Span span : text.spans()) result.append(span.text());
        return result.toString();
    }
}
