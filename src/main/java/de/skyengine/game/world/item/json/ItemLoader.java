package de.skyengine.game.world.item.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.item.CreativeTabs;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.item.archetype.ItemArchetypes;
import de.skyengine.utils.json.JsonMerge;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Lädt {@code game/items/*.json} und registriert die Items. Aufbau bewusst wie bei den Blöcken:
 * {@code parent}-Vererbung über {@link JsonMerge} (Presets in Unterordnern werden NICHT
 * registriert) und {@code ${id}}/{@code ${ns}}-Platzhalter.
 *
 * <p><b>Reihenfolge-Invariante:</b> jede Textur wird SOFORT bei der Registrierung über
 * {@link BlockTextures#layerOf} angemeldet, nie lazy. Passiert das erst nach dem Bau des
 * TextureArray (ChunkRenderer.init), liefert {@code layerOf} einen Index jenseits des Arrays und
 * das Item zeigt kommentarlos eine fremde Textur.
 */
public final class ItemLoader {

    private static final Logger LOGGER = LogManager.getLogger(ItemLoader.class.getName());
    private static final Gson GSON = new Gson();
    private static final int MAX_DEPTH = 10;

    public static void load(File directory) {
        if (directory == null || !directory.isDirectory()) return;   // items/ ist optional

        Map<String, JsonObject> lookup = new HashMap<>();
        collect(directory, directory, lookup);

        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, Comparator.comparing(File::getName));

        int loaded = 0;
        for (File file : files) {
            String key = stripExtension(file.getName());
            JsonObject merged = resolve(key, lookup, 0);
            if (merged == null) continue;
            applyVars(key, merged);
            try {
                if (register(GSON.fromJson(merged, ItemDefinition.class), key)) loaded++;
            } catch (Exception e) {
                LOGGER.error("Fehlerhafte Item-Definition: " + key, e);
            }
        }
        LOGGER.info(loaded + " Item-Definitionen geladen");
    }

    private static boolean register(ItemDefinition def, String key) {
        if (def.id == null || def.id.isBlank()) {
            LOGGER.error("Item-Definition ohne 'id': " + key);
            return false;
        }
        if (def.texture == null || def.texture.isBlank()) {
            LOGGER.error("Item-Definition ohne 'texture': " + def.id);
            return false;
        }
        Identifier id = Identifier.of(def.id);
        if (Registries.ITEM.contains(id)) {
            LOGGER.warning("Item bereits registriert, JSON ignoriert: " + id);
            return false;
        }

        /* Optionaler Ziel-Block (places_block): Blöcke sind zu diesem Zeitpunkt längst
           registriert (Items.bootstrap läuft nach dem Block-Bake). */
        de.skyengine.game.world.block.Block placedBlock = null;
        if (def.places_block != null && !def.places_block.isBlank()) {
            placedBlock = Registries.BLOCK.get(Identifier.of(def.places_block));
            if (placedBlock == null) {
                LOGGER.warning("places_block unbekannt bei " + id + ": " + def.places_block);
            }
        }

        Item item = ItemArchetypes.create(id, def);
        Registries.ITEM.register(id, item);
        if (def.crafting_remainder != null && !def.crafting_remainder.isBlank()) {
            Items.registerCraftingRemainder(id, Identifier.of(def.crafting_remainder));
        }
        if (def.command_only) Items.registerCommandOnly(id);
        if (placedBlock != null) Items.registerPlacer(placedBlock.getIdentifier(), item);
        if (!def.command_only) CreativeTabs.assign(id, CreativeTabs.parse(def.creative_tab));

        /* SOFORT anmelden — siehe Klassenkommentar. */
        BlockTextures.layerOf(def.texture);
        if (def.additional_textures != null) {
            for (String texture : def.additional_textures) BlockTextures.layerOf(texture);
        }
        return true;
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
                    LOGGER.error("Item-Datei fehlerhaft: " + rel, e);
                }
            }
        }
    }

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
        return parent == null ? self.deepCopy() : JsonMerge.deepMerge(parent, self);
    }

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

        var unresolved = JsonMerge.findUnresolved(merged);
        if (!unresolved.isEmpty()) {
            LOGGER.error("Unaufgeloeste Platzhalter in " + key + ": " + String.join(", ", unresolved));
        }
    }

    private static String stripExtension(String name) {
        return name.substring(0, name.length() - ".json".length());
    }

    private ItemLoader() {}
}
