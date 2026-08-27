package de.skyengine.game.world.recipe;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Liest Minecraft-nahe Crafting-JSONs sowie generische Prozess- und Brennstoffdateien. */
final class RecipeLoader {

    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogManager.getLogger(RecipeLoader.class.getName());

    static void load(List<ContentSource> sources, ItemTags tags, RecipeManager manager) {
        Map<Identifier, CraftingRecipe> crafting = new LinkedHashMap<>();
        Map<Identifier, ProcessingRecipe> processing = new LinkedHashMap<>();
        List<FuelRegistry.Fuel> fuels = new ArrayList<>();
        long order = 0;
        for (ContentSource source : sources) {
            File root = source.recipes();
            if (root != null && root.isDirectory()) for (File file : ItemTags.jsonFiles(root)) {
                Identifier id = new Identifier(source.namespace(), ItemTags.relativeId(root, file));
                try (FileReader reader = new FileReader(file)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    String type = qualified(json.get("type").getAsString(), source.namespace());
                    int priority = integer(json, "priority", 0);
                    if (type.equals("skyengine:crafting_shaped")) {
                        crafting.put(id, shaped(id, json, source.namespace(), tags, priority, order++));
                    } else if (type.equals("skyengine:crafting_shapeless")) {
                        crafting.put(id, shapeless(id, json, source.namespace(), tags, priority, order++));
                    } else if (type.equals("skyengine:processing")) {
                        processing.put(id, processing(id, json, source.namespace(), tags, priority, order++));
                    } else {
                        throw new IllegalArgumentException("Unbekannter Rezept-Serializer: " + type);
                    }
                } catch (Exception e) {
                    LOGGER.error("Fehlerhaftes Rezept " + id + " aus " + file, e);
                }
            }

            File fuelRoot = source.fuels();
            if (fuelRoot != null && fuelRoot.isDirectory()) for (File file : ItemTags.jsonFiles(fuelRoot)) {
                Identifier id = new Identifier(source.namespace(), ItemTags.relativeId(fuelRoot, file));
                try (FileReader reader = new FileReader(file)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    Identifier fuelType = Identifier.of(qualified(json.get("fuel_type").getAsString(), source.namespace()));
                    Ingredient ingredient = Ingredient.parse(json.get("ingredient"), source.namespace(), tags);
                    int burnTime = json.get("burn_time").getAsInt();
                    if (burnTime < 1) throw new IllegalArgumentException("burn_time < 1");
                    fuels.add(new FuelRegistry.Fuel(id, fuelType, ingredient, burnTime,
                            integer(json, "priority", 0), order++));
                } catch (Exception e) {
                    LOGGER.error("Fehlerhafter Brennstoff " + id + " aus " + file, e);
                }
            }
        }
        manager.build(crafting, processing, fuels);
    }

    private static ShapedCraftingRecipe shaped(Identifier id, JsonObject json, String namespace,
                                                ItemTags tags, int priority, long order) {
        JsonArray patternJson = json.getAsJsonArray("pattern");
        if (patternJson == null || patternJson.isEmpty()) throw new IllegalArgumentException("pattern fehlt");
        List<String> rows = new ArrayList<>();
        patternJson.forEach(row -> rows.add(row.getAsString()));
        int rawWidth = rows.getFirst().length();
        if (rawWidth == 0 || rows.stream().anyMatch(row -> row.length() != rawWidth)) {
            throw new IllegalArgumentException("Pattern-Zeilen sind nicht gleich breit");
        }
        int minX = rawWidth, minY = rows.size(), maxX = -1, maxY = -1;
        for (int y = 0; y < rows.size(); y++) for (int x = 0; x < rawWidth; x++) {
            if (rows.get(y).charAt(x) == ' ') continue;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        if (maxX < 0) throw new IllegalArgumentException("Leeres Pattern");
        int width = maxX - minX + 1, height = maxY - minY + 1;
        if (width > 9 || height > 9) throw new IllegalArgumentException("Pattern groesser als 9x9");
        JsonObject key = json.getAsJsonObject("key");
        List<Ingredient> ingredients = new ArrayList<>(width * height);
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            char symbol = rows.get(y).charAt(x);
            if (symbol == ' ') ingredients.add(null);
            else {
                JsonElement definition = key == null ? null : key.get(String.valueOf(symbol));
                if (definition == null) throw new IllegalArgumentException("Pattern-Symbol ohne key: " + symbol);
                ingredients.add(Ingredient.parse(definition, namespace, tags));
            }
        }
        return new ShapedCraftingRecipe(id, recipeType(json, namespace), width, height,
                ingredients, result(json.get("result"), namespace), priority, order);
    }

    private static ShapelessCraftingRecipe shapeless(Identifier id, JsonObject json, String namespace,
                                                      ItemTags tags, int priority, long order) {
        JsonArray values = json.getAsJsonArray("ingredients");
        if (values == null) throw new IllegalArgumentException("ingredients fehlt");
        List<Ingredient> ingredients = new ArrayList<>();
        values.forEach(value -> ingredients.add(Ingredient.parse(value, namespace, tags)));
        return new ShapelessCraftingRecipe(id, recipeType(json, namespace), ingredients,
                result(json.get("result"), namespace), priority, order);
    }

    private static ProcessingRecipe processing(Identifier id, JsonObject json, String namespace,
                                                ItemTags tags, int priority, long order) {
        JsonArray inputsJson = json.getAsJsonArray("inputs");
        if (inputsJson == null) throw new IllegalArgumentException("inputs fehlt");
        List<ProcessingRecipe.CountedIngredient> inputs = new ArrayList<>();
        for (JsonElement element : inputsJson) {
            JsonObject object = element.getAsJsonObject();
            inputs.add(new ProcessingRecipe.CountedIngredient(
                    Ingredient.parse(object, namespace, tags), integer(object, "count", 1)));
        }
        JsonElement outputJson = json.has("outputs") ? json.get("outputs") : json.get("result");
        List<ItemStack> outputs = new ArrayList<>();
        if (outputJson.isJsonArray()) outputJson.getAsJsonArray().forEach(value -> outputs.add(result(value, namespace)));
        else outputs.add(result(outputJson, namespace));
        Identifier type = Identifier.of(qualified(json.get("recipe_type").getAsString(), namespace));
        return new ProcessingRecipe(id, type, inputs, outputs, integer(json, "duration", 200), priority, order);
    }

    private static Identifier recipeType(JsonObject json, String namespace) {
        return Identifier.of(json.has("recipe_type")
                ? qualified(json.get("recipe_type").getAsString(), namespace)
                : RecipeManager.CRAFTING.toString());
    }

    private static ItemStack result(JsonElement element, String namespace) {
        if (element == null) throw new IllegalArgumentException("result fehlt");
        String id;
        int count = 1;
        if (element.isJsonPrimitive()) id = element.getAsString();
        else {
            JsonObject object = element.getAsJsonObject();
            if (object.has("id")) id = object.get("id").getAsString();
            else if (object.has("item")) id = object.get("item").getAsString();
            else throw new IllegalArgumentException("result braucht id/item");
            count = integer(object, "count", 1);
        }
        Item item = Items.get(Identifier.of(qualified(id, namespace)));
        if (item == null) throw new IllegalArgumentException("Unbekanntes Ergebnis-Item: " + id);
        if (count < 1 || count > item.getMaxStackSize()) throw new IllegalArgumentException("Ungueltige Ergebnismenge: " + count);
        return new ItemStack(item, count);
    }

    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static String qualified(String value, String namespace) {
        return value.contains(":") ? value : namespace + ":" + value;
    }

    private RecipeLoader() {}
}
