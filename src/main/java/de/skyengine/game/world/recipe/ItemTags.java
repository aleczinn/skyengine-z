package de.skyengine.game.world.recipe;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.Items;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Beim Bootstrap aufgeloeste Item-Tags fuer Rezeptzutaten und Brennstoffe. */
public final class ItemTags {

    private static final Logger LOGGER = LogManager.getLogger(ItemTags.class.getName());
    private static final Gson GSON = new Gson();

    private final Map<Identifier, Set<Item>> values;

    private ItemTags(Map<Identifier, Set<Item>> values) {
        this.values = Map.copyOf(values);
    }

    public Set<Item> get(Identifier id) {
        return this.values.getOrDefault(id, Set.of());
    }

    public static ItemTags load(List<ContentSource> sources) {
        Map<Identifier, List<String>> definitions = new LinkedHashMap<>();
        for (ContentSource source : sources) {
            File root = source.itemTags();
            if (root == null || !root.isDirectory()) continue;
            List<File> files = jsonFiles(root);
            for (File file : files) {
                Identifier id = new Identifier(source.namespace(), relativeId(root, file));
                try (FileReader reader = new FileReader(file)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    boolean replace = json.has("replace") && json.get("replace").getAsBoolean();
                    List<String> target = definitions.computeIfAbsent(id, ignored -> new ArrayList<>());
                    if (replace) target.clear();
                    JsonArray entries = json.getAsJsonArray("values");
                    if (entries == null) throw new IllegalArgumentException("values fehlt");
                    entries.forEach(value -> target.add(qualify(value.getAsString(), source.namespace())));
                } catch (Exception e) {
                    LOGGER.error("Fehlerhafter Item-Tag " + id + " aus " + file, e);
                }
            }
        }

        Map<Identifier, Set<Item>> resolved = new HashMap<>();
        for (Identifier id : definitions.keySet()) {
            resolve(id, definitions, resolved, new ArrayDeque<>());
        }
        LOGGER.info(resolved.size() + " Item-Tags geladen");
        return new ItemTags(resolved);
    }

    private static Set<Item> resolve(Identifier id, Map<Identifier, List<String>> definitions,
                                     Map<Identifier, Set<Item>> resolved, ArrayDeque<Identifier> path) {
        Set<Item> cached = resolved.get(id);
        if (cached != null) return cached;
        if (path.contains(id)) {
            LOGGER.error("Zyklischer Item-Tag: " + path + " -> " + id);
            return Set.of();
        }
        List<String> entries = definitions.get(id);
        if (entries == null) {
            LOGGER.warning("Item-Tag nicht gefunden: " + id);
            return Set.of();
        }
        path.addLast(id);
        Set<Item> out = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                out.addAll(resolve(Identifier.of(entry.substring(1)), definitions, resolved, path));
            } else {
                Item item = Items.get(Identifier.of(entry));
                if (item == null) LOGGER.warning("Unbekanntes Item in Tag " + id + ": " + entry);
                else out.add(item);
            }
        }
        path.removeLast();
        Set<Item> immutable = Set.copyOf(out);
        resolved.put(id, immutable);
        return immutable;
    }

    static String qualify(String value, String namespace) {
        boolean tag = value.startsWith("#");
        String raw = tag ? value.substring(1) : value;
        String qualified = raw.contains(":") ? raw : namespace + ":" + raw;
        return tag ? "#" + qualified : qualified;
    }

    static List<File> jsonFiles(File root) {
        List<File> out = new ArrayList<>();
        collect(root, out);
        out.sort(Comparator.comparing(File::getPath));
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collect(file, out);
            else if (file.getName().endsWith(".json")) out.add(file);
        }
    }

    static String relativeId(File root, File file) {
        String path = root.toURI().relativize(file.toURI()).getPath();
        return path.substring(0, path.length() - 5);
    }
}
