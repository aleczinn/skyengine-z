package de.skyengine.graphics.gui;

import de.skyengine.game.command.ChatManager;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.text.RichTextWrapper;
import de.skyengine.graphics.gui.text.Span;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minecraft-artige Chatzeilen links unten mit stiltreuem Wortumbruch. */
public final class ChatHud {

    public static final int VISIBLE_LINES = 10;
    private static final long CLOSED_LIFETIME_MS = 10_000;
    private static final float TEXT_SIZE = GuiText.NORMAL;
    public static final float DEFAULT_WIDTH = 320F;
    public static final float LINE_HEIGHT = 12F;
    private static final Color4 WHITE = new Color4(1F, 1F, 1F, 1F);

    public void render(GuiManager gui, ChatManager chat, float bottom, boolean open) {
        render(gui, chat, bottom, open, 0);
    }

    public void render(GuiManager gui, ChatManager chat, float bottom, boolean open, int scrollLines) {
        long now = System.currentTimeMillis();
        float lineHeight = LINE_HEIGHT;
        float chatWidth = Math.min(DEFAULT_WIDTH, gui.vWidth() - 4F);
        List<VisualLine> lines = visualLines(gui, chat.messages(), chatWidth, now, open);
        if (lines.isEmpty()) return;
        int offset = clampScroll(scrollLines, lines.size());
        int visible = Math.min(VISIBLE_LINES, lines.size() - offset);

        gui.sprites().begin(gui.vWidth(), gui.vHeight());
        for (int drawn = 0; drawn < visible; drawn++) {
            VisualLine line = lines.get(lines.size() - 1 - offset - drawn);
            float y = bottom - (drawn + 1) * lineHeight;
            gui.sprites().drawRect(2, y, chatWidth, lineHeight, 0F, 0F, 0F, 0.5F);
        }
        if (open && lines.size() > VISIBLE_LINES) {
            float trackHeight = VISIBLE_LINES * lineHeight;
            float barHeight = Math.max(2F, trackHeight * VISIBLE_LINES / lines.size());
            float travel = trackHeight - barHeight;
            float ratio = maxScroll(lines.size()) == 0 ? 0F : (float) offset / maxScroll(lines.size());
            float barY = bottom - trackHeight + travel * (1F - ratio);
            gui.sprites().drawRect(0F, barY, 1F, barHeight, 0.75F, 0.75F, 0.75F, 0.9F);
        }
        gui.sprites().end();

        gui.enableScissor(2F, 0F, chatWidth, gui.vHeight());
        gui.font().begin(gui.vWidth(), gui.vHeight());
        for (int drawn = 0; drawn < visible; drawn++) {
            VisualLine line = lines.get(lines.size() - 1 - offset - drawn);
            float y = bottom - (drawn + 1) * lineHeight;
            gui.font().drawRich(line.wrapped.text(), 4, y, TEXT_SIZE, WHITE, true);
        }
        gui.font().end();
        gui.disableScissor();
    }

    /** Liefert beim Klick auf einen auch nach Umbruch korrekt zugeordneten Span dessen Zielpfad. */
    public Path clickedTarget(GuiManager gui, ChatManager chat, float bottom,
                              double mouseX, double mouseY, int scrollLines) {
        float lineHeight = LINE_HEIGHT;
        float chatWidth = Math.min(DEFAULT_WIDTH, gui.vWidth() - 4F);
        List<VisualLine> lines = visualLines(gui, chat.messages(), chatWidth,
                System.currentTimeMillis(), true);
        int offset = clampScroll(scrollLines, lines.size());
        int visible = Math.min(VISIBLE_LINES, lines.size() - offset);
        for (int drawn = 0; drawn < visible; drawn++) {
            VisualLine line = lines.get(lines.size() - 1 - offset - drawn);
            float y = bottom - (drawn + 1) * lineHeight;
            if (mouseY < y || mouseY >= y + lineHeight) continue;
            ChatManager.ClickAction action = line.message.clickAction();
            if (action == null) return null;

            float x = 4F;
            List<Span> spans = line.wrapped.text().spans();
            for (int i = 0; i < spans.size(); i++) {
                Span span = spans.get(i);
                float width = gui.font().getStringWidth(span.text(), TEXT_SIZE, span.style());
                if (line.wrapped.sourceSpanIndices().get(i) == action.spanIndex()
                        && mouseX >= x && mouseX < x + width) return action.target();
                x += width;
            }
            return null;
        }
        return null;
    }

    public int visualLineCount(GuiManager gui, ChatManager chat) {
        float chatWidth = Math.min(DEFAULT_WIDTH, gui.vWidth() - 4F);
        return visualLines(gui, chat.messages(), chatWidth, System.currentTimeMillis(), true).size();
    }

    public static int maxScroll(int visualLineCount) {
        return Math.max(0, visualLineCount - VISIBLE_LINES);
    }

    public static int clampScroll(int requested, int visualLineCount) {
        return Math.clamp(requested, 0, maxScroll(visualLineCount));
    }

    private static List<VisualLine> visualLines(GuiManager gui,
                                                 List<ChatManager.ChatMessage> messages,
                                                 float chatWidth, long now, boolean includeExpired) {
        List<VisualLine> result = new ArrayList<>();
        float textWidth = Math.max(1F, chatWidth - 4F);
        for (ChatManager.ChatMessage message : messages) {
            if (!includeExpired && now - message.createdAtMillis() > CLOSED_LIFETIME_MS) continue;
            for (RichTextWrapper.Line line : RichTextWrapper.wrap(message.text(), gui.font(), TEXT_SIZE, textWidth)) {
                result.add(new VisualLine(message, line));
            }
        }
        return result;
    }

    private record VisualLine(ChatManager.ChatMessage message, RichTextWrapper.Line wrapped) {}
}
