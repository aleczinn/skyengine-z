package de.skyengine.graphics.gui;

import de.skyengine.game.command.ChatManager;
import de.skyengine.graphics.color.Color4;

import java.util.List;

/** Minecraft-artige Chatzeilen links unten, offen dauerhaft und geschlossen kurz sichtbar. */
public final class ChatHud {

    private static final int MAX_VISIBLE = 10;
    private static final long CLOSED_LIFETIME_MS = 10_000;
    private static final float TEXT_SIZE = GuiText.NORMAL;
    /** Minecrafts Standard-Chatbreite bei unveraenderten Chat-Einstellungen. */
    public static final float DEFAULT_WIDTH = 320F;
    private static final Color4 WHITE = new Color4(1F, 1F, 1F, 1F);

    public void render(GuiManager gui, ChatManager chat, float bottom, boolean open) {
        List<ChatManager.ChatMessage> messages = chat.messages();
        if (messages.isEmpty()) return;
        long now = System.currentTimeMillis();
        float lineHeight = gui.font().lineHeight(TEXT_SIZE) + 1F;
        float chatWidth = Math.min(DEFAULT_WIDTH, gui.vWidth() - 4F);
        int drawn = 0;

        gui.sprites().begin(gui.vWidth(), gui.vHeight());
        for (int i = messages.size() - 1; i >= 0 && drawn < MAX_VISIBLE; i--) {
            ChatManager.ChatMessage message = messages.get(i);
            if (!open && now - message.createdAtMillis() > CLOSED_LIFETIME_MS) continue;
            float y = bottom - (++drawn) * lineHeight;
            float width = Math.min(chatWidth, gui.font().width(message.text(), TEXT_SIZE) + 4F);
            gui.sprites().drawRect(2, y, width, lineHeight, 0F, 0F, 0F, 0.5F);
        }
        gui.sprites().end();

        drawn = 0;
        gui.enableScissor(2F, 0F, chatWidth, gui.vHeight());
        gui.font().begin(gui.vWidth(), gui.vHeight());
        for (int i = messages.size() - 1; i >= 0 && drawn < MAX_VISIBLE; i--) {
            ChatManager.ChatMessage message = messages.get(i);
            if (!open && now - message.createdAtMillis() > CLOSED_LIFETIME_MS) continue;
            float y = bottom - (++drawn) * lineHeight;
            gui.font().drawRich(message.text(), 4, y, TEXT_SIZE, WHITE, true);
        }
        gui.font().end();
        gui.disableScissor();
    }
}
