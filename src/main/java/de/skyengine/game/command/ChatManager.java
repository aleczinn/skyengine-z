package de.skyengine.game.command;

import de.skyengine.graphics.gui.text.RichText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Chat-Verlauf und Befehlsausfuehrung, unabhaengig vom konkreten Chat-GUI. */
public final class ChatManager {

    public static final int MAX_MESSAGES = 100;
    private final CommandDispatcher dispatcher = new CommandDispatcher();
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<String> history = new ArrayList<>();

    public ChatManager() {
        this.register(new GiveCommand());
        this.register(new DimensionCommand());
        this.register(new StructureCommand());
        for (String name : List.of("wand", "rotate", "flip", "preview", "paste", "undo", "redo")) {
            this.register(new WorldEditCommand(name));
        }
    }

    /** Zentraler Erweiterungspunkt fuer spaetere Engine- oder Mod-Befehle. */
    public void register(Command command) {
        this.dispatcher.register(command);
    }

    public void submit(CommandContext context, String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return;
        if (this.history.isEmpty() || !this.history.getLast().equals(value)) {
            if (this.history.size() == MAX_MESSAGES) this.history.removeFirst();
            this.history.add(value);
        }
        CommandResult result = this.dispatcher.execute(context, value);
        for (String message : result.messages()) {
            this.addMessage((result.success() ? "§f" : "§c") + message);
        }
    }

    public void addMessage(String markup) {
        this.addMessage(RichText.parse(markup), null);
    }

    /** Fügt formatierten Text mit genau einem anklickbaren Span hinzu. */
    public void addMessage(RichText text, int clickableSpan, Path target) {
        this.addMessage(text, new ClickAction(clickableSpan, target.toAbsolutePath().normalize()));
    }

    private void addMessage(RichText text, ClickAction action) {
        if (this.messages.size() == MAX_MESSAGES) this.messages.removeFirst();
        this.messages.add(new ChatMessage(text, System.currentTimeMillis(), action));
    }

    public List<String> suggestions(CommandContext context, String input) {
        return this.dispatcher.suggest(context, input);
    }

    public String hint(String input) {
        return this.dispatcher.hint(input);
    }

    public List<ChatMessage> messages() {
        return List.copyOf(this.messages);
    }

    public List<String> history() {
        return List.copyOf(this.history);
    }

    public record ChatMessage(RichText text, long createdAtMillis, ClickAction clickAction) {
    }

    /** Aktion eines einzelnen RichText-Spans; derzeit ausschließlich für Screenshot-Dateien. */
    public record ClickAction(int spanIndex, Path target) {
    }
}
