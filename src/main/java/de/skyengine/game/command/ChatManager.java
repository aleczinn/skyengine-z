package de.skyengine.game.command;

import de.skyengine.graphics.gui.text.RichText;

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
        this.register(new TimeCommand());
        this.register(new TimeSpeedCommand());
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
        this.addMessage((result.success() ? "§f" : "§c") + result.message());
    }

    public void addMessage(String markup) {
        if (this.messages.size() == MAX_MESSAGES) this.messages.removeFirst();
        this.messages.add(new ChatMessage(RichText.parse(markup), System.currentTimeMillis()));
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

    public record ChatMessage(RichText text, long createdAtMillis) {
    }
}
