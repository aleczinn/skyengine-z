package de.skyengine.game.command;

import java.util.List;

/** Erweiterungspunkt fuer einen Chat-Befehl. Argumente enthalten den Befehlsnamen nicht. */
public interface Command {

    /** Befehls-Namespace; normale Spielbefehle nutzen '/', Editorbefehle '//'. */
    default String prefix() { return "/"; }

    String name();

    /** Argument-Signatur fuer die graue Eingabevorschau, z.B. {@code <item> [amount]}. */
    default String usage() {
        return "";
    }

    /** Gruppierte Syntax fuer Inline-Hinweise; einfache Befehle nutzen weiterhin {@link #usage()}. */
    default CommandSyntax syntax(List<String> arguments) {
        return CommandSyntax.legacy(usage());
    }

    /** Erkennt den Beginn des abschliessenden Optionsbereichs fuer die Hint-Berechnung. */
    default boolean isOptionToken(String token) {
        return false;
    }

    CommandResult execute(CommandContext context, List<String> arguments);

    /** Vorschlaege fuer das aktuelle (letzte) Argument, ohne den bereits geschriebenen Prefix. */
    default List<String> suggest(CommandContext context, List<String> arguments, String current) {
        return List.of();
    }
}
