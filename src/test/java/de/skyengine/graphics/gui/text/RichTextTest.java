package de.skyengine.graphics.gui.text;

import de.skyengine.graphics.gui.font.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RichTextTest {

    @Test
    void parsesMinecraftColorsStylesAndReset() {
        RichText text = RichText.parse("normal §cred §lbold §rnormal");

        assertEquals(4, text.spans().size());
        assertEquals(FontStyle.REGULAR, text.spans().get(0).style());
        assertSame(TextColors.parse("red"), text.spans().get(1).color());
        assertEquals(FontStyle.BOLD, text.spans().get(2).style());
        assertSame(TextColors.parse("red"), text.spans().get(2).color());
        assertEquals(FontStyle.REGULAR, text.spans().get(3).style());
        assertEquals(null, text.spans().get(3).color());
    }

    @Test
    void legacyColorResetsTheActiveFontStyle() {
        RichText text = RichText.parse("§lbold§agreen");

        assertEquals(FontStyle.BOLD, text.spans().get(0).style());
        assertEquals(FontStyle.REGULAR, text.spans().get(1).style());
        assertSame(TextColors.parse("green"), text.spans().get(1).color());
    }

    @Test
    void supportsTagsAndLegacyCodesInTheSameText() {
        RichText text = RichText.parse("<gold>tag §lbold</> plain");

        assertSame(TextColors.parse("gold"), text.spans().get(0).color());
        assertEquals(FontStyle.BOLD, text.spans().get(1).style());
        assertEquals("tag bold plain", visible(text));
    }

    @Test
    void splitsDescriptionsAtNewlinesAndBreakTagsWithDefaultColor() {
        List<RichText> lines = RichText.parseLines("one\ntwo<br>three", TextColors.GRAY);

        assertEquals(List.of("one", "two", "three"), lines.stream().map(RichTextTest::visible).toList());
        for (RichText line : lines) assertSame(TextColors.GRAY, line.spans().getFirst().color());
    }

    @Test
    void resetAndClosingTagReturnToTheConfiguredBaseColor() {
        RichText text = RichText.parse("<red>red §rgray</> still gray", TextColors.GRAY);

        assertSame(TextColors.GRAY, text.spans().get(1).color());
        assertSame(TextColors.GRAY, text.spans().get(2).color());
    }

    @Test
    void stripRemovesSupportedMarkupButPreservesUnsupportedCodes() {
        assertEquals("Red bold\nnext §nunderlined",
                RichText.strip("§cRed <b>bold</b><br>next §nunderlined"));
    }

    private static String visible(RichText text) {
        StringBuilder result = new StringBuilder();
        for (Span span : text.spans()) result.append(span.text());
        return result.toString();
    }
}
