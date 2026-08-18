package de.skyengine.game.world.block.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.skyengine.core.resource.ResourceId;
import de.skyengine.core.resource.ResourceManager;
import de.skyengine.core.resource.Resources;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bildet einen {@link BlockState} auf sein(e) Modell(e) ab (Minecraft-Stil):
 * {@code variants} (State-Key -> Modell + x/y-Rotation) oder {@code multipart}
 * ({@code when}-Bedingungen). Liefert gebackene Quads UND Kollisions-Boxen.
 * Ergebnisse werden je State-ID gecacht (IDs stehen ab {@code BlockRegistry.bake}).
 */
public final class BlockStateModels {

    private static final Logger LOGGER = LogManager.getLogger(BlockStateModels.class.getName());

    private static final Map<String, JsonObject> STATES = new HashMap<>();
    private static final Map<Integer, ModelLoader.Baked> CACHE = new ConcurrentHashMap<>();
    private static final ModelLoader.Baked EMPTY = new ModelLoader.Baked(new BakedQuad[0], new AABB[0]);

    /**
     * Übernimmt die von {@link de.skyengine.game.world.block.json.BlockJson} aufgelösten
     * Dokumente — dieselben Instanzen, die auch der {@code BlockLoader} als DTO liest. Die
     * Render-Sektion ({@code variants}/{@code multipart}/{@code icon_*}) steckt in denselben
     * Dateien, sieht also automatisch dieselbe parent-Vererbung.
     */
    public static void load(Map<String, JsonObject> definitions) {
        STATES.clear();
        CACHE.clear();
        STATES.putAll(definitions);
        LOGGER.info(STATES.size() + " Blockstates geladen");
    }

    /** Default-Rendersektionen plus reine visuelle Pack-Blockstates. */
    public static void loadResources(Map<String, JsonObject> definitions) {
        STATES.clear();
        CACHE.clear();
        STATES.putAll(definitions);
        try {
            for (Map.Entry<ResourceId, ResourceManager.Match> entry
                    : Resources.get().listResolved("blockstates/").entrySet()) {
                ResourceId id = entry.getKey();
                if (!id.namespace().equals(ResourceId.DEFAULT_NAMESPACE)
                        || !id.path().startsWith("blockstates/") || !id.path().endsWith(".json")) continue;
                String key = id.path().substring("blockstates/".length(), id.path().length() - ".json".length());
                try (InputStreamReader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                    STATES.put(key, JsonParser.parseReader(reader).getAsJsonObject());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Blockstate fehlerhaft: " + id, e);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Blockstates konnten nicht aufgelistet werden", e);
        }
        LOGGER.info(STATES.size() + " Blockstates aus Ressourcen-Stack geladen");
    }

    /** Cache nach einem visuellen Reload leeren. */
    public static void clearCache() {
        CACHE.clear();
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
     * Texturpfad für ein einzelnes flaches Item-Sprite (optionales {@code "icon_item"},
     * MC-Item-Look — z.B. Tür-Sprite, Pflanzen). {@code null} = kein Item-Sprite definiert.
     */
    public static String iconItem(Block block) {
        JsonObject root = STATES.get(block.getIdentifier().path());
        if (root == null || !root.has("icon_item")) return null;
        return root.get("icon_item").getAsString();
    }

    /**
     * Backt das Inventar-/Icon-Modell eines Blocks. Optionales {@code "inventory_model"} im Blockstate
     * (z.B. Zaun mit Armen, kleine Tür, flache Glasscheibe) hat Vorrang vor dem Default-State-Modell —
     * so sieht das Icon aus wie in Minecraft, ohne die Welt-Darstellung zu beeinflussen.
     */
    public static ModelLoader.Baked bakeInventory(Block block) {
        ModelLoader.Baked override = inventoryOverride(block);
        return override != null ? override : bake(block, block.getDefaultState());
    }

    /**
     * Nur das explizit deklarierte {@code inventory_model}, sonst {@code null}. Für Aufrufer, die
     * zwischen „Block hat ein eigenes Item-Modell" und „nimm den Default-State" unterscheiden
     * müssen — die First-/Third-Person-Hand zeigt sonst beim Zaun nur den nackten Pfosten.
     */
    public static ModelLoader.Baked inventoryOverride(Block block) {
        JsonObject root = STATES.get(block.getIdentifier().path());
        if (root == null) return null;
        if (root.has("inventory_models")) {
            List<BakedQuad[]> quads = new ArrayList<>();
            List<AABB> boxes = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("inventory_models")) {
                JsonObject part = element.getAsJsonObject();
                ModelLoader.Baked baked = ModelLoader.bake(
                        part.get("model").getAsString(),
                        part.has("x") ? part.get("x").getAsInt() : 0,
                        part.has("y") ? part.get("y").getAsInt() : 0);
                float[] translation = part.has("translation")
                        ? floatArray(part.getAsJsonArray("translation"))
                        : new float[]{0, 0, 0};
                ModelLoader.Baked moved = translate(baked,
                        translation[0] / 16F, translation[1] / 16F, translation[2] / 16F);
                quads.add(moved.quads());
                Collections.addAll(boxes, moved.boxes());
            }
            return new ModelLoader.Baked(
                    BlockModels.concat(quads.toArray(new BakedQuad[0][])),
                    boxes.toArray(new AABB[0]));
        }
        if (!root.has("inventory_model")) return null;
        int x = root.has("inventory_x") ? root.get("inventory_x").getAsInt() : 0;
        int y = root.has("inventory_y") ? root.get("inventory_y").getAsInt() : 0;
        return ModelLoader.bake(root.get("inventory_model").getAsString(), x, y);
    }

    /**
     * Modell, dessen {@code display}-Sektion fuer das zusammengesetzte Inventarmodell gilt.
     * Ohne Override bleibt das bisherige gleichnamige Blockmodell der Transform-Ursprung.
     */
    public static String inventoryDisplayModel(Block block) {
        String override = inventoryDisplayOverrideModel(block);
        if (override != null) return override;
        return "block/" + block.getIdentifier().path();
    }

    /**
     * Explizites Display-Modell fuer Sonder-Items, oder {@code null}. Anders als
     * {@link #inventoryDisplayModel(Block)} faellt diese Methode nicht auf das normale Blockmodell
     * zurueck; der GUI-Renderer kann so gezielt nur Sondermodelle (z.B. Betten) behandeln, ohne die
     * bewusst angepasste Standard-Isometrie aller Block-Icons zu veraendern.
     */
    public static String inventoryDisplayOverrideModel(Block block) {
        JsonObject root = STATES.get(block.getIdentifier().path());
        return root != null && root.has("inventory_display_model")
                ? root.get("inventory_display_model").getAsString()
                : null;
    }

    private static float[] floatArray(JsonArray values) {
        if (values.size() != 3) throw new IllegalArgumentException("translation braucht [x,y,z]");
        return new float[]{values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat()};
    }

    private static ModelLoader.Baked translate(ModelLoader.Baked baked, float dx, float dy, float dz) {
        BakedQuad[] out = new BakedQuad[baked.quads().length];
        for (int i = 0; i < out.length; i++) {
            BakedQuad source = baked.quads()[i];
            float[] vertices = source.vertices().clone();
            for (int p = 0; p < vertices.length; p += 5) {
                vertices[p] += dx;
                vertices[p + 1] += dy;
                vertices[p + 2] += dz;
            }
            out[i] = new BakedQuad(vertices, source.textureLayer(), source.cullFace(), source.face(),
                    source.brightness(), source.tint(), source.tintType());
        }
        AABB[] boxes = new AABB[baked.boxes().length];
        for (int i = 0; i < boxes.length; i++) {
            AABB box = baked.boxes()[i];
            boxes[i] = new AABB(box.minX + dx, box.minY + dy, box.minZ + dz,
                    box.maxX + dx, box.maxY + dy, box.maxZ + dz);
        }
        return new ModelLoader.Baked(out, boxes);
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
        JsonElement v = variants.has(key) ? variants.get(key) : null;
        if (v == null) v = partialMatch(variants, state);
        if (v == null) v = variants.get("");
        if (v == null) {
            LOGGER.warning("Variante '" + key + "' fehlt für " + state.getBlock().getIdentifier());
            return EMPTY;
        }
        return applyVariant(firstObject(v));
    }

    /**
     * MC-Semantik: Variant-Keys nennen nur die RELEVANTEN Properties — {@code "facing=north,
     * open=false"} trifft auch States, die zusätzlich {@code powered} tragen. Nur so überleben
     * die Preset-Blockstates (Tür, Falltür) das Hinzufügen neuer Properties. Der exakte Voll-Key
     * gewinnt weiterhin zuerst (Aufrufer); hier zählt der erste Treffer in JSON-Reihenfolge —
     * Keys müssen sich wie in MC gegenseitig ausschließen.
     */
    private static JsonElement partialMatch(JsonObject variants, BlockState state) {
        for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
            if (e.getKey().isEmpty()) continue;
            if (matchesKey(e.getKey(), state)) return e.getValue();
        }
        return null;
    }

    private static boolean matchesKey(String key, BlockState state) {
        for (String pair : key.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) return false;
            String actual = propByName(state, pair.substring(0, eq));
            if (!pair.substring(eq + 1).equals(actual)) return false;
        }
        return true;
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

    /** Gilt für {@code variants} UND für die {@code apply}-Objekte von {@code multipart}. */
    private static ModelLoader.Baked applyVariant(JsonObject v) {
        String model = v.get("model").getAsString();
        int x = v.has("x") ? v.get("x").getAsInt() : 0;
        int y = v.has("y") ? v.get("y").getAsInt() : 0;
        /* uvlock (MC): Geometrie drehen, Textur weltachsenfest lassen — Treppe/Zaun ja, Stamm nie. */
        boolean uvlock = v.has("uvlock") && v.get("uvlock").getAsBoolean();
        return ModelLoader.bake(model, x, y, uvlock);
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
