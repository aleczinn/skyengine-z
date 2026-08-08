package de.skyengine.core.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.skyengine.core.file.Files;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lokalisierung: verschachtelte JSONs unter {@code game/lang/<code>.json} (GSON, Lade-Muster wie
 * BlockLoader), beim Laden zu Punkt-Keys abgeflacht ({@code "gui": {"done": ...}} → {@code gui.done}).
 * Rein statisch und CPU-seitig — ein Sprachwechsel zur Laufzeit ist nur
 * {@link #load(String)} + Re-Init des offenen Screens (alle Texte entstehen in {@code init()}).
 *
 * <p>Fallback-Kette: aktive Sprache → en_us → bei {@code block.}/{@code item.}-Keys der
 * prettifizierte Pfad ("oak_planks" → "Oak Planks"), sonst der Key selbst (macht fehlende
 * Einträge im Menü sichtbar statt zu crashen).
 */
public final class I18n {

    /** Fallback-Sprache — deckt über den Prettify-Mechanismus auch ungepflegte Block-Namen ab. */
    public static final String FALLBACK_CODE = "en_us";

    private static final Logger LOGGER = LogManager.getLogger(I18n.class.getName());
    private static final File LANG_DIR = new File(Files.RESOURCES_PATH, "game/lang");

    private static Map<String, String> active = Map.of();
    private static Map<String, String> fallback = Map.of();
    private static String activeCode = "";

    /** Eine anlegbare Sprache: Datei-Code + nativer Anzeigename (Top-Level-Key {@code name}). */
    public record Language(String code, String nativeName) {}

    private I18n() {}

    /** Lädt (bzw. wechselt auf) die Sprache {@code code}; die en_us-Fallback-Map nur einmalig. */
    public static void load(String code) {
        if (fallback.isEmpty()) {
            fallback = readLangFile(FALLBACK_CODE);
        }
        active = FALLBACK_CODE.equals(code) ? fallback : readLangFile(code);
        activeCode = code;
        LOGGER.info("Sprache geladen: " + code + " (" + active.size() + " Eintraege)");
    }

    /** Code der aktuell aktiven Sprache (z. B. "de_de"). */
    public static String code() {
        return activeCode;
    }

    /** Übersetzter Text zu einem Key (Fallback-Kette siehe Klassen-Doku). */
    public static String tr(String key) {
        String value = active.get(key);
        if (value == null) value = fallback.get(key);
        return value != null ? value : missing(key);
    }

    /** Wie {@link #tr(String)}, formatiert Platzhalter ({@code %s}/{@code %d}) mit den Args. */
    public static String tr(String key, Object... args) {
        return String.format(Locale.ROOT, tr(key), args);
    }

    /** true, wenn der Schlüssel in aktiver Sprache oder Fallback tatsächlich vorhanden ist. */
    public static boolean has(String key) {
        return active.containsKey(key) || fallback.containsKey(key);
    }

    /** Alle anlegbaren Sprachen (eine JSON-Datei = eine Sprache), Name immer nativ. */
    public static List<Language> available() {
        List<Language> result = new ArrayList<>();
        File[] files = LANG_DIR.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String code = file.getName().substring(0, file.getName().length() - ".json".length());
                String name = readLangFile(code).get("name");
                result.add(new Language(code, name != null ? name : code));
            }
        }
        return result;
    }

    private static Map<String, String> readLangFile(String code) {
        File file = new File(LANG_DIR, code + ".json");
        try (FileReader reader = new FileReader(file)) {
            Map<String, String> map = new HashMap<>();
            flatten("", JsonParser.parseReader(reader).getAsJsonObject(), map);
            return map;
        } catch (Exception e) {
            LOGGER.error("Sprachdatei konnte nicht geladen werden: " + file.getPath(), e);
        }
        return Map.of();
    }

    /** Flacht die verschachtelte Struktur zu Punkt-Keys ab ({@code gui.done} usw.). */
    private static void flatten(String prefix, JsonObject obj, Map<String, String> out) {
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue().isJsonObject()) {
                flatten(key, entry.getValue().getAsJsonObject(), out);
            } else {
                out.put(key, entry.getValue().getAsString());
            }
        }
    }

    /** Fehlender Eintrag: Block-/Item-Namen prettifizieren, alles andere als Key anzeigen. */
    private static String missing(String key) {
        if (!key.startsWith("block.") && !key.startsWith("item.")) return key;
        String path = key.substring(key.lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder(path.length());
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
