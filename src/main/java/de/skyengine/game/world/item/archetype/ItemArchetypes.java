package de.skyengine.game.world.item.archetype;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.item.BucketItem;
import de.skyengine.game.world.item.FlintAndSteelItem;
import de.skyengine.game.world.item.FoodItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemFrameItem;
import de.skyengine.game.world.item.MinecartItem;
import de.skyengine.game.world.item.SimpleItem;
import de.skyengine.game.world.item.ShearsItem;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.game.world.item.ToolTier;
import de.skyengine.game.world.item.ToolType;
import de.skyengine.game.world.item.json.ItemDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registry der Item-Archetypen. Neue Itemfamilien erweitern nur diese Tabelle und ihr DTO. */
public final class ItemArchetypes {

    private static final Map<String, ItemArchetype> VALUES = new LinkedHashMap<>();

    static {
        register("item", (id, def) -> new SimpleItem(id, maxStack(def), def.texture,
                resolveBlock(def.places_block, id, "places_block")));
        register("food", (id, def) -> {
            if (def.food == null) throw new IllegalArgumentException("food-Daten fehlen bei " + id);
            return new FoodItem(id, def.food.nutrition, def.food.saturation, def.texture);
        });
        register("bucket", (id, def) -> {
            if (def.bucket == null) throw new IllegalArgumentException("bucket-Daten fehlen bei " + id);
            Block fluid = def.bucket.fluid == null || def.bucket.fluid.isBlank() ? null
                    : resolveBlock(def.bucket.fluid, id, "bucket.fluid");
            return new BucketItem(id, fluid, def.texture, maxStack(def));
        });
        register("tool", (id, def) -> {
            if (def.tool == null) throw new IllegalArgumentException("tool-Daten fehlen bei " + id);
            ToolType type = ToolType.byName(def.tool.type);
            ToolTier tier = ToolTier.byName(def.tool.tier);
            if (type == null || tier == null) {
                throw new IllegalArgumentException("Ungueltiges Tool bei " + id + ": "
                        + def.tool.type + "/" + def.tool.tier);
            }
            return new ToolItem(id, type, tier, def.texture);
        });
        register("flint_and_steel", (id, def) -> new FlintAndSteelItem(id, def.texture));
        register("item_frame", (id, def) -> new ItemFrameItem(id, def.texture));
        register("minecart", (id, def) -> new MinecartItem(id, def.texture));
        register("shears", (id, def) -> new ShearsItem(id, def.texture));
    }

    public static void register(String name, ItemArchetype archetype) {
        if (VALUES.putIfAbsent(name, archetype) != null) {
            throw new IllegalStateException("Item-Archetyp doppelt registriert: " + name);
        }
    }

    public static Item create(Identifier id, ItemDefinition definition) {
        String name = definition.archetype == null || definition.archetype.isBlank()
                ? "item" : definition.archetype;
        ItemArchetype archetype = VALUES.get(name);
        if (archetype == null) throw new IllegalArgumentException("Unbekannter Item-Archetyp: " + name);
        return archetype.create(id, definition);
    }

    private static int maxStack(ItemDefinition def) {
        return def.max_stack == null ? Item.DEFAULT_MAX_STACK : def.max_stack;
    }

    private static Block resolveBlock(String value, Identifier itemId, String field) {
        if (value == null || value.isBlank()) return null;
        Block block = Registries.BLOCK.get(Identifier.of(value));
        if (block == null) throw new IllegalArgumentException(field + " unbekannt bei " + itemId + ": " + value);
        return block;
    }

    private ItemArchetypes() {}
}
