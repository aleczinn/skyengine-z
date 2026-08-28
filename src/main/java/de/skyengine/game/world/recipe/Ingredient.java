package de.skyengine.game.world.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Set;

/** Unveraenderliche Menge aller Items, die eine Rezeptzutat erfuellen. */
public final class Ingredient {

    private final Set<Item> accepted;

    public Ingredient(Set<Item> accepted) {
        if (accepted == null || accepted.isEmpty()) throw new IllegalArgumentException("Leere Zutat");
        this.accepted = Set.copyOf(accepted);
    }

    public boolean test(ItemStack stack) {
        return stack != null && !stack.isEmpty() && this.accepted.contains(stack.getItem());
    }

    public boolean accepts(Item item) {
        return item != null && this.accepted.contains(item);
    }

    public Set<Item> acceptedItems() {
        return this.accepted;
    }

    public static Ingredient parse(JsonElement json, String namespace, ItemTags tags) {
        Set<Item> items = new LinkedHashSet<>();
        collect(json, namespace, tags, items);
        return new Ingredient(items);
    }

    private static void collect(JsonElement json, String namespace, ItemTags tags, Set<Item> out) {
        if (json == null || json.isJsonNull()) throw new IllegalArgumentException("Zutat fehlt");
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(entry -> collect(entry, namespace, tags, out));
            return;
        }
        String value;
        if (json.isJsonPrimitive()) {
            value = json.getAsString();
        } else {
            JsonObject object = json.getAsJsonObject();
            if (object.has("item")) value = object.get("item").getAsString();
            else if (object.has("tag")) value = "#" + object.get("tag").getAsString();
            else throw new IllegalArgumentException("Zutat braucht item oder tag: " + object);
        }
        value = ItemTags.qualify(value, namespace);
        if (value.startsWith("#")) {
            Set<Item> tagged = tags.get(Identifier.of(value.substring(1)));
            if (tagged.isEmpty()) throw new IllegalArgumentException("Leerer/unbekannter Item-Tag: " + value);
            out.addAll(tagged);
            return;
        }
        Item item = Items.get(Identifier.of(value));
        if (item == null) throw new IllegalArgumentException("Unbekanntes Item: " + value);
        out.add(item);
    }
}
