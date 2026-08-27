package de.skyengine.game.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Gruppierte Befehls-Syntax fuer stabile, lokalisierbare Inline-Hinweise. */
public record CommandSyntax(List<Group> groups, String trailingArguments) {

    public CommandSyntax {
        groups = List.copyOf(groups);
        trailingArguments = trailingArguments == null ? "" : trailingArguments;
    }

    public static CommandSyntax of(List<Group> groups, String trailingArguments) {
        return new CommandSyntax(groups, trailingArguments);
    }

    public static CommandSyntax legacy(String usage) {
        if (usage == null || usage.isBlank()) return new CommandSyntax(List.of(), "");
        return new CommandSyntax(Arrays.stream(usage.trim().split("\\s+"))
                .map(Group::raw).toList(), "");
    }

    /** Restliche Syntax nach bereits eingegebenen Positionsargumenten. */
    public String hint(int suppliedPositionals, boolean optionTailStarted) {
        // Sobald das erste Flag/Option begonnen wurde, duerfen gemaess der globalen
        // Konvention keine Positionsargumente mehr folgen.
        if (optionTailStarted) return "";
        int consumed = Math.max(0, suppliedPositionals);
        List<String> remaining = new ArrayList<>();
        for (Group group : groups) {
            int used = Math.min(consumed, group.arity());
            consumed -= used;
            String rendered = group.render(used);
            if (!rendered.isEmpty()) remaining.add(rendered);
        }
        if (!trailingArguments.isBlank()) remaining.add(trailingArguments);
        return String.join(" ", remaining);
    }

    public record Group(List<String> names, boolean optional, boolean raw) {
        public Group {
            if (names == null || names.isEmpty()) throw new IllegalArgumentException("Leere Syntaxgruppe");
            names = List.copyOf(names);
        }

        public static Group required(String... names) {
            return new Group(List.of(names), false, false);
        }

        public static Group optional(String... names) {
            return new Group(List.of(names), true, false);
        }

        public static Group raw(String value) {
            return new Group(List.of(value), false, true);
        }

        int arity() { return names.size(); }

        String render(int consumed) {
            if (consumed >= names.size()) return "";
            List<String> visible = names.subList(consumed, names.size());
            if (raw) return String.join(" ", visible);
            if (optional) return "[" + String.join(" ", visible) + "]";
            return visible.stream().map(name -> "<" + name + ">")
                    .reduce((left, right) -> left + " " + right).orElse("");
        }
    }
}
