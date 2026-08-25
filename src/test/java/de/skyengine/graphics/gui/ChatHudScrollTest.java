package de.skyengine.graphics.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChatHudScrollTest {
    @Test
    void clampsScrollToWrappedHistory() {
        assertEquals(0, ChatHud.maxScroll(10));
        assertEquals(7, ChatHud.maxScroll(17));
        assertEquals(0, ChatHud.clampScroll(-4, 17));
        assertEquals(3, ChatHud.clampScroll(3, 17));
        assertEquals(7, ChatHud.clampScroll(200, 17));
    }
}
