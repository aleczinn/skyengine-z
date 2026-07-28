package de.skyengine.utils.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zwei Bausteine für die JSON-Vererbung (Blöcke wie Items): rekursives Zusammenführen von
 * Eltern- und Kind-Dokument sowie {@code ${name}}-Platzhalter in Strings.
 *
 * <p>Beide Operationen arbeiten auf Kopien bzw. auf einem frisch gemergten Dokument — ein
 * Eltern-Dokument wird von mehreren Kindern geteilt und darf nie mutiert werden.
 */
public final class JsonMerge {

    /** {@code ${name}} — bewusst nur Wortzeichen, damit ein einzelnes '$' im Text harmlos bleibt. */
    private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)}");

    /**
     * Führt Kind auf Eltern zusammen: gemeinsame Objekt-Felder werden rekursiv gemergt, alles
     * andere überschreibt das Kind. Arrays werden ERSETZT, nicht angehängt — ein Kind, das eine
     * {@code variants}-Liste neu definiert, will sie ersetzen, nicht verlängern.
     */
    public static JsonObject deepMerge(JsonObject parent, JsonObject child) {
        JsonObject out = parent.deepCopy();
        for (Map.Entry<String, JsonElement> e : child.entrySet()) {
            JsonElement base = out.get(e.getKey());
            if (base != null && base.isJsonObject() && e.getValue().isJsonObject()) {
                out.add(e.getKey(), deepMerge(base.getAsJsonObject(), e.getValue().getAsJsonObject()));
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        return out;
    }

    /** Ersetzt {@code ${name}} in allen String-WERTEN des Baums (Schlüssel bleiben unberührt). */
    public static void substitute(JsonElement root, Map<String, String> vars) {
        if (root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            /* Kopie der Schlüssel: add() auf denselben Schlüssel würde sonst die Iteration stören. */
            for (String key : new ArrayList<>(o.keySet())) {
                JsonElement v = o.get(key);
                if (isString(v)) {
                    o.addProperty(key, apply(v.getAsString(), vars));
                } else {
                    substitute(v, vars);
                }
            }
        } else if (root.isJsonArray()) {
            JsonArray a = root.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement v = a.get(i);
                if (isString(v)) {
                    a.set(i, new JsonPrimitive(apply(v.getAsString(), vars)));
                } else {
                    substitute(v, vars);
                }
            }
        }
    }

    /** Unbekannte Variablen bleiben unverändert stehen — der Aufrufer meldet sie über {@link #findUnresolved}. */
    public static String apply(String text, Map<String, String> vars) {
        if (text.indexOf('$') < 0) return text;
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Namen aller nach der Substitution übrig gebliebenen Platzhalter (leer = alles aufgelöst). */
    public static java.util.List<String> findUnresolved(JsonElement root) {
        java.util.List<String> out = new ArrayList<>();
        Matcher m = VAR.matcher(root.toString());
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private JsonMerge() {}
}
