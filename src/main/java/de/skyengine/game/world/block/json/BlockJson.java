package de.skyengine.game.world.block.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.skyengine.utils.json.JsonMerge;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest den {@code blocks/}-Ordner und liefert je registriertem Block das FERTIG aufgelöste
 * JSON-Dokument: {@code parent}-Kette zusammengeführt und {@code ${var}}-Platzhalter ersetzt.
 *
 * <p>Der Rückgabewert geht an BEIDE Leser derselben Dateien — {@link BlockLoader} (Gson-DTO) und
 * {@link de.skyengine.game.world.block.model.BlockStateModels} (variants/multipart). Dass es
 * dieselbe Map-Instanz ist, ist Absicht: so können Definition und Render-Sektion gar nicht
 * auseinanderlaufen.
 *
 * <p>Dateien in Unterordnern (üblicherweise {@code preset/}) werden NICHT registriert, stehen
 * aber als {@code parent} zur Verfügung. Die Registrierungsreihenfolge ist exakt die alte
 * Dateisortierung — davon hängen die Runtime-State-IDs innerhalb eines Builds ab.
 */
public final class BlockJson {

    private static final Logger LOGGER = LogManager.getLogger(BlockJson.class.getName());
    private static final Gson GSON = new Gson();

    /** Schutz gegen Zyklen in der parent-Kette. */
    private static final int MAX_DEPTH = 10;

    public static LinkedHashMap<String, JsonObject> load(File directory) {
        LinkedHashMap<String, JsonObject> out = new LinkedHashMap<>();
        if (directory == null || !directory.isDirectory()) {
            LOGGER.warning("Block-Ordner nicht gefunden: " + directory);
            return out;
        }

        /* Rekursiv alles einlesen (Key = Relativpfad ohne .json) — nur als Nachschlagewerk
           für parent-Verweise wie "preset/cube". */
        Map<String, JsonObject> lookup = new HashMap<>();
        collect(directory, directory, lookup);

        /* Registriert werden ausschließlich die Dateien auf oberster Ebene, in EXAKT der
           bisherigen Sortierung (Dateiname MIT Endung) — die Runtime-IDs hängen daran. */
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            LOGGER.warning("Keine Block-Definitionen in " + directory.getAbsolutePath());
            return out;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            String key = stripExtension(file.getName());
            JsonObject merged = resolve(key, lookup, 0);
            if (merged == null) continue;
            applyVars(key, merged);
            out.put(key, merged);
        }
        return out;
    }

    private static void collect(File root, File dir, Map<String, JsonObject> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collect(root, f, out);
            } else if (f.getName().endsWith(".json")) {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                try (FileReader r = new FileReader(f)) {
                    out.put(stripExtension(rel), GSON.fromJson(r, JsonObject.class));
                } catch (Exception e) {
                    LOGGER.error("Block-Datei fehlerhaft: " + rel, e);
                }
            }
        }
    }

    /** Löst die parent-Kette auf; das Ergebnis ist immer eine frische Kopie. */
    private static JsonObject resolve(String key, Map<String, JsonObject> lookup, int depth) {
        JsonObject self = lookup.get(key);
        if (self == null) {
            LOGGER.error("parent nicht gefunden: " + key);
            return null;
        }
        if (depth >= MAX_DEPTH) {
            LOGGER.error("parent-Kette zu tief oder zyklisch bei: " + key);
            return self.deepCopy();
        }
        JsonElement parentRef = self.get("parent");
        if (parentRef == null) return self.deepCopy();

        JsonObject parent = resolve(parentRef.getAsString(), lookup, depth + 1);
        /* Fehlender Parent ist bereits gemeldet — das Kind allein ist die beste Rettung. */
        return parent == null ? self.deepCopy() : JsonMerge.deepMerge(parent, self);
    }

    /**
     * Setzt die Platzhalter: eingebaut sind {@code ${id}} (Pfad ohne Namespace) und {@code ${ns}},
     * dazu die Einträge des optionalen {@code vars}-Objekts. Die vars-Werte dürfen NUR die
     * eingebauten Variablen benutzen, nicht sich gegenseitig — eine zweite Auflösungsebene
     * bräuchte Fixpunkt-Iteration und wäre von der Feldreihenfolge abhängig.
     */
    private static void applyVars(String key, JsonObject merged) {
        String id = merged.has("id") ? merged.get("id").getAsString() : key;
        int colon = id.indexOf(':');

        Map<String, String> builtin = new HashMap<>();
        builtin.put("ns", colon >= 0 ? id.substring(0, colon) : "skyengine");
        builtin.put("id", colon >= 0 ? id.substring(colon + 1) : id);

        Map<String, String> vars = new HashMap<>(builtin);
        if (merged.has("vars") && merged.get("vars").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : merged.getAsJsonObject("vars").entrySet()) {
                vars.put(e.getKey(), JsonMerge.apply(e.getValue().getAsString(), builtin));
            }
        }

        JsonMerge.substitute(merged, vars);

        List<String> unresolved = JsonMerge.findUnresolved(merged);
        if (!unresolved.isEmpty()) {
            LOGGER.error("Unaufgeloeste Platzhalter in " + key + ": " + String.join(", ", unresolved));
        }
    }

    private static String stripExtension(String name) {
        return name.substring(0, name.length() - ".json".length());
    }

    private BlockJson() {}
}
