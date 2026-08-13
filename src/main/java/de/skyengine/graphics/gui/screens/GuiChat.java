package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.game.command.ChatManager;
import de.skyengine.game.command.CommandContext;
import de.skyengine.graphics.Screenshot;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.ChatHud;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.widget.TextField;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Nicht pausierender Singleplayer-Chat zur Eingabe und Vervollstaendigung von Befehlen. */
public final class GuiChat extends GuiScreen {

    private static final float INPUT_HEIGHT = 10F;
    private static final float CHAT_BOTTOM_OFFSET = 40F;
    private static final int MAX_INPUT_LENGTH = 256;
    private static final Color4 SUGGESTION = new Color4(0.7F, 0.7F, 0.7F, 1F);
    private static final Color4 ACTIVE_SUGGESTION = new Color4(1F, 1F, 0.33F, 1F);
    private static final Color4 SYNTAX_HINT = new Color4(0.45F, 0.45F, 0.45F, 1F);

    private final ChatManager chat;
    private final CommandContext context;
    private final ChatHud chatHud;
    private String draft;
    private String historyDraft = "";
    private int historyIndex = -1;
    private TextField input;
    private List<String> completions = List.of();
    private int completionIndex;
    private boolean completionApplied;

    public GuiChat(ChatManager chat, CommandContext context, ChatHud chatHud, String initial) {
        super(null);
        this.chat = chat;
        this.context = context;
        this.chatHud = chatHud;
        this.draft = initial;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        if (this.input != null) this.draft = this.input.getText();
        this.components.clear();
        float width = Math.min(ChatHud.DEFAULT_WIDTH, vW - 4F);
        this.input = new TextField(width, INPUT_HEIGHT, MAX_INPUT_LENGTH, null)
                .borderless()
                .text(this.draft)
                .placeholder(I18n.tr("chat.prompt"));
        this.input.layoutAt(2F, vH - INPUT_HEIGHT - 2F);
        this.input.setFocused(true);
        this.components.add(this.input);
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        if (this.completions.isEmpty()) {
            this.completions = this.chat.suggestions(this.context, this.input.getText());
        }
        float inputY = this.input.y;
        /* Minecraft haengt die letzte Chatzeile auch bei offener Eingabe oberhalb der Hotbar ein;
           sie sitzt nicht unmittelbar auf dem Eingabefeld am unteren Bildschirmrand. */
        this.chatHud.render(gui, this.chat, gui.vHeight() - CHAT_BOTTOM_OFFSET, true);

        gui.sprites().begin(gui.vWidth(), gui.vHeight());
        gui.sprites().drawRect(this.input.x, inputY, this.input.w, INPUT_HEIGHT,
                0F, 0F, 0F, 0.65F);
        this.input.renderBackground(gui, mouseX, mouseY);
        if (!this.completions.isEmpty()) {
            float line = gui.font().lineHeight(GuiText.NORMAL) + 1F;
            int visible = Math.min(8, this.completions.size());
            gui.sprites().drawRect(this.input.x, inputY - 2F - visible * line, this.input.w,
                    visible * line, 0F, 0F, 0F, 0.8F);
        }
        gui.sprites().end();

        gui.font().begin(gui.vWidth(), gui.vHeight());
        this.input.renderText(gui, mouseX, mouseY);
        String hint = this.chat.hint(this.input.getText());
        float hintX = this.input.x + 1F
                + gui.font().getStringWidth(this.input.getText(), GuiText.NORMAL);
        if (!hint.isEmpty() && hintX < this.input.x + this.input.w - 1F) {
            gui.font().drawString(hint, hintX,
                    this.input.y + (this.input.h - gui.font().lineHeight(GuiText.NORMAL)) / 2F,
                    GuiText.NORMAL, SYNTAX_HINT);
        }
        if (!this.completions.isEmpty()) {
            float line = gui.font().lineHeight(GuiText.NORMAL) + 1F;
            int visible = Math.min(8, this.completions.size());
            for (int i = 0; i < visible; i++) {
                int candidate = (this.completionIndex + i) % this.completions.size();
                Color4 color = this.completionApplied && candidate == this.completionIndex
                        ? ACTIVE_SUGGESTION : SUGGESTION;
                gui.font().drawStringWithShadow(this.completions.get(candidate), this.input.x + 2F,
                        inputY - 2F - (visible - i) * line, GuiText.NORMAL, color);
            }
        }
        gui.font().end();
    }

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Path target = this.chatHud.clickedTarget(gui, this.chat,
                    gui.vHeight() - CHAT_BOTTOM_OFFSET, mouseX, mouseY);
            if (target != null) {
                CompletableFuture.runAsync(() -> Screenshot.showInFileManager(target.toFile()))
                        .whenComplete((unused, error) -> {
                            if (error != null) {
                                SkyEngine.get().addTaskToRenderThread(() ->
                                        this.chat.addMessage("§c" + I18n.tr("chat.screenshot_open_failed")));
                            }
                        });
                return true;
            }
        }
        return super.mousePressed(gui, mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            this.chat.submit(this.context, this.input.getText());
            gui.close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            this.moveHistory(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            this.moveHistory(1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            this.complete();
            return true;
        }
        this.resetCompletion();
        return super.keyPressed(gui, key);
    }

    @Override
    public boolean charTyped(GuiManager gui, int codepoint) {
        this.resetCompletion();
        return super.charTyped(gui, codepoint);
    }

    private void complete() {
        if (this.completions.isEmpty()) {
            this.completions = this.chat.suggestions(this.context, this.input.getText());
            this.completionIndex = 0;
        } else if (this.completionApplied) {
            this.completionIndex = (this.completionIndex + 1) % this.completions.size();
        }
        if (!this.completions.isEmpty()) {
            this.input.text(this.completions.get(this.completionIndex));
            this.completionApplied = true;
        }
    }

    private void resetCompletion() {
        this.completions = List.of();
        this.completionIndex = 0;
        this.completionApplied = false;
    }

    private void moveHistory(int direction) {
        List<String> history = this.chat.history();
        if (history.isEmpty()) return;
        if (this.historyIndex < 0) {
            if (direction > 0) return;
            this.historyDraft = this.input.getText();
            this.historyIndex = history.size() - 1;
        } else {
            this.historyIndex += direction;
            if (this.historyIndex >= history.size()) {
                this.historyIndex = -1;
                this.input.text(this.historyDraft);
                return;
            }
            this.historyIndex = Math.max(0, this.historyIndex);
        }
        this.input.text(history.get(this.historyIndex));
        this.resetCompletion();
    }

    @Override
    protected void renderBackground(GuiManager gui) {
        // Die Welt bleibt wie im Minecraft-Chat sichtbar und wird nicht abgedunkelt.
    }
}
