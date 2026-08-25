package de.skyengine.game.command;

import java.util.List;

/** Ergebnis eines Befehls; jede Ausgabe wird als eigene Chatnachricht angelegt. */
public record CommandResult(boolean success, List<String> messages) {

    public CommandResult { messages = messages == null ? List.of() : List.copyOf(messages); }

    /** Kompatible Ein-Zeilen-Sicht fuer Tests und einfache Aufrufer. */
    public String message() { return String.join("\n", messages); }

    public static CommandResult success(String message) {
        return new CommandResult(true, List.of(message));
    }

    public static CommandResult success(List<String> messages) { return new CommandResult(true, messages); }

    public static CommandResult error(String message) {
        return new CommandResult(false, List.of(message));
    }

    public static CommandResult error(List<String> messages) { return new CommandResult(false, messages); }
}
