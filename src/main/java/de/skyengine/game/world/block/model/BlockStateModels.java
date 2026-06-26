package de.skyengine.game.world.block.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bildet einen {@link BlockState} auf sein(e) Modell(e) ab (Minecraft-Stil):
 * {@code variants} (State-Key -> Modell + x/y-Rotation) oder {@code multipart}
 * ({@code when}-Bedingungen). Liefert gebackene Quads UND Kollisions-Boxen.
 * Ergebnisse werden je State-ID gecacht (IDs stehen ab {@code BlockRegistry.bake}).
 */
public final class BlockStateModels {

    private static final Logger LOGGER = LogManager.getLogger(BlockStateModels.class.getName());
    private static final Gson GSON = new Gson();

    private static final Map<String, JsonObject> STATES = new HashMap<>();
    private static final Map<Short, ModelLoader.Baked> CACHE = new ConcurrentHashMap<>();
    private static final ModelLoader.Baked EMPTY = new ModelLoader.Baked(new BakedQuad[0], new AABB[0]);

    public static void load(File dir) {
        STATES.clear();
        CACHE.clear();
        if (dir == null || !dir.isDirectory()) {
            LOGGER.warning("Block-Ordner nicht gefunden: " + dir);
            return;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - ".json".length());
            try (FileReader r = new FileReader(f)) {
                STATES.put(name, GSON.fromJson(r, JsonObject.class));
            } catch (Exception e) {
                LOGGER.error("Blockstate fehlerhaft: " + name, e);
            }
        }
        LOGGER.info(STATES.size() + " Blockstates geladen");
    }

    public static ModelLoader.Baked bake(Block block, BlockState state) {
        ModelLoader.Baked cached = CACHE.get(state.getId());
        if (cached != null) return cached;
        ModelLoader.Baked baked = bakeInternal(block, state);
        CACHE.put(state.getId(), baked);
        return baked;
    }

    /**
     * Texturpfade für ein flaches 2D-Icon (MC-Item-Sprite-Stil) aus optionalem {@code "icon_flat"}:
     * Liste von Pfaden, von unten nach oben gestapelt (1 = volles Quad wie Glasscheibe, 2 = Mini-Tür
     * aus Unter-/Oberhälfte). {@code null}, wenn der Block als 3D-Würfel/-Modell gerendert wird.
     */
    public static String[] flatIcon(Block block) {
        JsonObject root = STATES.get(block.getIdentifier().path());
        if (root == null || !root.has("icon_flat")) return null;
        JsonArray arr = root.getAsJsonArray("icon_flat");
        String[] out = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsString();
        return out;
    }

    /**
     * Backt das Inventar-/Icon-Modell eines Blocks. Optionales {@code "inventory_model"} im Blockstate
     * (z.B. Zaun mit Armen, kleine Tür, flache Glasscheibe) hat Vorrang vor dem Default-State-Modell —
     * so sieht das Icon aus wie in Minecraft, ohne die Welt-Darstellung zu beeinflussen.
     */
    public static ModelLoader.Baked bakeInventory(Block block) {
        JsonObject root = STATES.get(block.getIdentifier().path());
        if (root != null && root.has("inventory_model")) {
            int x = root.has("inventory_x") ? root.get("inventory_x").getAsInt() : 0;
            int y = root.has("inventory_y") ? root.get("inventory_y").getAsInt() : 0;
            return ModelLoader.bake(root.get("inventory_model").getAsString(), x, y);
        }
        return bake(block, block.getDefaultState());
    }

    private static ModelLoader.Baked bakeInternal(Block block, BlockState state) {
        String path = block.getIdentifier().path();
        JsonObject root = STATES.get(path);
        if (root != null) {
            if (root.has("multipart")) return bakeMultipart(root.getAsJsonArray("multipart"), state);
            if (root.has("variants")) return bakeVariant(root.getAsJsonObject("variants"), state);
        }
        /* Auto-Default: ohne variants/multipart rendert ein Block über das gleichnamige Modell
           block/<id> (ohne Rotation) — spart die Boilerplate-Sektion bei einfachen Würfeln. */
        return ModelLoader.bake("block/" + path, 0, 0);
    }

    private static ModelLoader.Baked bakeVariant(JsonObject variants, BlockState state) {
        String key = variantKey(state);
        JsonElement v = variants.has(key) ? variants.get(key) : variants.get("");
        if (v == null) {
            LOGGER.warning("Variante '" + key + "' fehlt für " + state.getBlock().getIdentifier());
            return EMPTY;
        }
        return applyVariant(firstObject(v));
    }

    private static ModelLoader.Baked bakeMultipart(JsonArray parts, BlockState state) {
        List<BakedQuad[]> quads = new ArrayList<>();
        List<AABB> boxes = new ArrayList<>();
        for (JsonElement pe : parts) {
            JsonObject part = pe.getAsJsonObject();
            if (part.has("when") && !matches(part.getAsJsonObject("when"), state)) continue;
            ModelLoader.Baked b = applyVariant(firstObject(part.get("apply")));
            quads.add(b.quads());
            Collections.addAll(boxes, b.boxes());
        }
        return new ModelLoader.Baked(
                BlockModels.concat(quads.toArray(new BakedQuad[0][])),
                boxes.toArray(new AABB[0]));
    }

    private static ModelLoader.Baked applyVariant(JsonObject v) {
        String model = v.get("model").getAsString();
        int x = v.has("x") ? v.get("x").getAsInt() : 0;
        int y = v.has("y") ? v.get("y").getAsInt() : 0;
        return ModelLoader.bake(model, x, y);
    }

    private static JsonObject firstObject(JsonElement e) {
        return e.isJsonArray() ? e.getAsJsonArray().get(0).getAsJsonObject() : e.getAsJsonObject();
    }

    private static boolean matches(JsonObject when, BlockState state) {
        for (Map.Entry<String, JsonElement> e : when.entrySet()) {
            String actual = propByName(state, e.getKey());
            if (!e.getValue().getAsString().equals(actual)) return false;
        }
        return true;
    }

    private static String variantKey(BlockState state) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<Property<?>, Object> e : state.getValues().entrySet()) {
            sorted.put(e.getKey().getName(), valueString(e.getValue()));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String propByName(BlockState state, String name) {
        for (Map.Entry<Property<?>, Object> e : state.getValues().entrySet()) {
            if (e.getKey().getName().equals(name)) return valueString(e.getValue());
        }
        return null;
    }

    private static String valueString(Object v) {
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Enum<?> e) return e.name().toLowerCase(Locale.ROOT);
        return String.valueOf(v);
    }

    private BlockStateModels() {}
}
