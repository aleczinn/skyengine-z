package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parser fuer die globale Konvention: erst Positionen, danach Flags und key=value-Optionen. */
final class TrailingArguments {

    record Parsed(List<String> positionals, Set<String> flags, Map<String, String> options) {
        boolean flag(String canonical) { return flags.contains(canonical); }
        String option(String key, String fallback) { return options.getOrDefault(key, fallback); }
    }

    static Parsed parse(List<String> arguments, Map<String, String> allowedFlags,
                        Set<String> allowedOptions) {
        List<String> positionals = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, String> options = new LinkedHashMap<>();
        boolean tail = false;
        for (String original : arguments) {
            String token = original.toLowerCase(Locale.ROOT);
            String canonicalFlag = allowedFlags.get(token);
            if (canonicalFlag != null) {
                tail = true;
                if (!flags.add(canonicalFlag)) throw new IllegalArgumentException(
                        I18n.tr("command.arguments.duplicate", original));
                continue;
            }
            if (looksLikeFlag(token)) throw new IllegalArgumentException(
                    I18n.tr("command.arguments.unknown", original));

            int equals = original.indexOf('=');
            String key = equals > 0 ? original.substring(0, equals).toLowerCase(Locale.ROOT) : null;
            if (key != null && allowedOptions.contains(key)) {
                tail = true;
                if (options.putIfAbsent(key, original.substring(equals + 1)) != null) {
                    throw new IllegalArgumentException(I18n.tr("command.arguments.duplicate", key));
                }
                continue;
            }
            if (tail) throw new IllegalArgumentException(I18n.tr("command.arguments.options_last"));
            positionals.add(original);
        }
        return new Parsed(List.copyOf(positionals), Set.copyOf(flags), Map.copyOf(options));
    }

    static boolean optionToken(String token, Set<String> flags, Set<String> optionKeys) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (flags.contains(lower)) return true;
        int equals = lower.indexOf('=');
        return equals > 0 && optionKeys.contains(lower.substring(0, equals));
    }

    private static boolean looksLikeFlag(String value) {
        if (!value.startsWith("-") || value.equals("-")) return false;
        return !value.matches("-\\d+(?:\\.\\d+)?");
    }

    private TrailingArguments() {}
}
