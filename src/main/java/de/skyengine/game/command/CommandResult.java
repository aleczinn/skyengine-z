package de.skyengine.game.command;

/** Ergebnis eines Befehls; der Text darf Minecraft-Farbcodes enthalten. */
public record CommandResult(boolean success, String message) {

    public static CommandResult success(String message) {
        return new CommandResult(true, message);
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, message);
    }
}
