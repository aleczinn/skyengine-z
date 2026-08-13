package de.skyengine.game.command;

import de.skyengine.graphics.gui.text.RichText;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ChatManagerTest {

    @Test
    void storesAnOptionalClickableSpanAndNormalizedTarget() {
        ChatManager chat = new ChatManager();
        Path target = Path.of("screenshots", "..", "screenshots", "shot.png");

        chat.addMessage(RichText.plain("shot.png"), 0, target);
        ChatManager.ChatMessage clickable = chat.messages().getFirst();

        assertEquals(0, clickable.clickAction().spanIndex());
        assertEquals(target.toAbsolutePath().normalize(), clickable.clickAction().target());

        chat.addMessage("plain");
        assertNull(chat.messages().getLast().clickAction());
    }
}
