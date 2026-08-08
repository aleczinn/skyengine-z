package de.skyengine.game.world.item;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ersetzt benannte Tooltip-Platzhalter wie {@code %energy%} durch aktuelle Werte. */
public final class TooltipTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_]+)%");

    public static String resolve(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty() || variables == null || variables.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            if (value == null) continue; // unbekannt sichtbar lassen, damit Datenfehler auffallen
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private TooltipTemplate() {
    }
}
