package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry, Parser und Autovervollstaendigung fuer Slash-Befehle. */
public final class CommandDispatcher {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(Command command) {
        String name = command.name().toLowerCase(Locale.ROOT);
        if (this.commands.putIfAbsent(name, command) != null) {
            throw new IllegalArgumentException("Doppelter Befehl: " + name);
        }
    }

    public CommandResult execute(CommandContext context, String input) {
        String value = input == null ? "" : input.trim();
        if (!value.startsWith("/")) {
            return CommandResult.error(I18n.tr("chat.commands_only"));
        }
        String body = value.substring(1).trim();
        if (body.isEmpty()) return CommandResult.error(I18n.tr("chat.empty_command"));

        List<String> tokens = tokens(body);
        String name = tokens.removeFirst().toLowerCase(Locale.ROOT);
        Command command = this.commands.get(name);
        if (command == null) return CommandResult.error(I18n.tr("chat.unknown_command", name));
        return command.execute(context, List.copyOf(tokens));
    }

    /** Liefert vollstaendige Eingabezeilen, damit das GUI sie ohne Parserwissen einsetzen kann. */
    public List<String> suggest(CommandContext context, String input) {
        if (input == null || !input.startsWith("/")) return List.of();
        String body = input.substring(1);
        int firstSpace = body.indexOf(' ');
        if (firstSpace < 0) {
            String prefix = body.toLowerCase(Locale.ROOT);
            return this.commands.keySet().stream()
                    .filter(name -> name.startsWith(prefix))
                    .map(name -> "/" + name)
                    .toList();
        }

        String name = body.substring(0, firstSpace).toLowerCase(Locale.ROOT);
        Command command = this.commands.get(name);
        if (command == null) return List.of();
        String argumentText = body.substring(firstSpace + 1);
        boolean trailingSpace = argumentText.endsWith(" ");
        List<String> arguments = tokens(argumentText);
        String current = trailingSpace || arguments.isEmpty() ? "" : arguments.removeLast();
        List<String> candidates = command.suggest(context, List.copyOf(arguments), current);
        if (candidates.isEmpty()) return List.of();

        StringBuilder fixed = new StringBuilder("/").append(name).append(' ');
        for (String argument : arguments) fixed.append(argument).append(' ');
        List<String> result = new ArrayList<>(candidates.size());
        for (String candidate : candidates) result.add(fixed + candidate);
        return result;
    }

    /** Noch fehlender Teil der registrierten Befehlssignatur fuer eine Inline-Vorschau. */
    public String hint(String input) {
        if (input == null || !input.startsWith("/")) return "";
        String body = input.substring(1);
        int firstSpace = body.indexOf(' ');
        String name = (firstSpace < 0 ? body : body.substring(0, firstSpace)).toLowerCase(Locale.ROOT);
        Command command = this.commands.get(name);
        if (command == null) return "";
        String argumentText = firstSpace < 0 ? "" : body.substring(firstSpace + 1);
        boolean trailingSpace = firstSpace >= 0 && input.endsWith(" ");
        String remaining = command.usage(tokens(argumentText), trailingSpace);
        if (remaining == null || remaining.isBlank()) return "";
        return firstSpace >= 0 && trailingSpace ? remaining : " " + remaining;
    }

    private static List<String> tokens(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.trim().split("\\s+")) {
            if (!token.isEmpty()) result.add(token);
        }
        return result;
    }
}
