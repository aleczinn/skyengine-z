package de.skyengine.game.command;

import java.util.List;

/** Erweiterungspunkt fuer einen Chat-Befehl. Argumente enthalten den Befehlsnamen nicht. */
public interface Command {

    String name();

    /** Argument-Signatur fuer die graue Eingabevorschau, z.B. {@code <item> [amount]}. */
    default String usage() {
        return "";
    }

    CommandResult execute(CommandContext context, List<String> arguments);

    /** Vorschlaege fuer das aktuelle (letzte) Argument, ohne den bereits geschriebenen Prefix. */
    default List<String> suggest(CommandContext context, List<String> arguments, String current) {
        return List.of();
    }
}
