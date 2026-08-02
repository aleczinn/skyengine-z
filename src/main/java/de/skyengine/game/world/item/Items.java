package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.block.content.ContentSources;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.item.json.ItemLoader;

/**
 * Item-Registry-Zugriff + Bootstrap. Jeder Nicht-Luft-Block bekommt automatisch ein
 * {@link BlockItem} mit derselben {@link Identifier} - so lässt sich ein Block als Item
 * lagern/halten. Muss NACH der Block-Registrierung laufen.
 */
public final class Items {

    /**
     * Rückwärts-Zuordnung Block → platzierendes Item für Blöcke OHNE Auto-BlockItem
     * ({@code no_item} + fremdes Item mit {@code places_block}). Gefüllt vom
     * {@link ItemLoader}; Abfrage über {@link #forBlock}.
     */
    private static final java.util.Map<Identifier, Item> PLACER_BY_BLOCK = new java.util.HashMap<>();

    public static void bootstrap() {
        PLACER_BY_BLOCK.clear();
        for (Block block : Registries.BLOCK.values()) {
            if (block.isAir()) continue;
            /* Fluids (Wasser/Lava) bekommen kein Block-Item — sie werden nur über Eimer gehandhabt. */
            if (block.isFluid()) continue;
            Identifier id = block.getIdentifier();
            /* no_item: ein Material-Item mit places_block übernimmt (Redstone-Staub) —
               nur die Registrierung überspringen, die Icon-Anmeldung unten bleibt. */
            if (!block.hasNoItem() && !Registries.ITEM.contains(id)) {
                Registries.ITEM.register(id, new BlockItem(block));
            }

            /* Icon-Texturen (icon_item/icon_flat) in den Block-Atlas aufnehmen — MUSS vor dem
               TextureArray-Bau laufen, sonst bekämen sie beim ersten Zeichnen einen
               Layer-Index außerhalb des Arrays (gleiche Falle wie bei den Eimern unten). */
            String iconItem = BlockStateModels.iconItem(block);
            if (iconItem != null) BlockTextures.layerOf(iconItem);
            String[] iconFlat = BlockStateModels.flatIcon(block);
            if (iconFlat != null) {
                for (String path : iconFlat) BlockTextures.layerOf(path);
            }
        }

        /* Werkzeuge: 7 Materialien x 4 Typen (IDs/Texturen im MC-Schema: wooden_pickaxe, golden_axe, ...). */
        for (ToolTier tier : ToolTier.values()) {
            for (ToolType type : ToolType.values()) {
                String name = tier.prefix() + "_" + type.name().toLowerCase();
                Identifier id = Identifier.of("skyengine:" + name);
                String texture = "game/textures/item/" + name + ".png";
                if (!Registries.ITEM.contains(id)) {
                    Registries.ITEM.register(id, new ToolItem(id, type, tier, texture));
                }
                CreativeTabs.assign(id, "tools");
                BlockTextures.layerOf(texture); // vor dem TextureArray-Bau registrieren
            }
        }

        /* Eimer sind eigenständige Items (keine Block-Items). Leer stapelt wie in MC bis 16. */
        Block water = Registries.BLOCK.get(Identifier.of("skyengine:water"));
        Block lava = Registries.BLOCK.get(Identifier.of("skyengine:lava"));
        registerBucket("skyengine:bucket", null, "game/textures/item/bucket.png", 16);
        registerBucket("skyengine:water_bucket", water, "game/textures/item/water_bucket.png", 1);
        registerBucket("skyengine:lava_bucket", lava, "game/textures/item/lava_bucket.png", 1);

        /* Essen (MC-Werte: nutrition in Halb-Icons, saturation). */
        registerFood("skyengine:apple", 4, 2.4F, "game/textures/item/apple.png");
        registerFood("skyengine:bread", 5, 6.0F, "game/textures/item/bread.png");

        /* Datengetriebene Items zuletzt: die Java-Registrierungen oben haben Vorrang, und wir
           sind weiterhin lange vor dem TextureArray-Bau (ChunkRenderer.init). */
        for (ContentSource source : ContentSources.all()) ItemLoader.load(source.items());

        /* Creative-Tabs erst hier: build() läuft über die fertige Item-Registry und legt damit
           die Reihenfolge innerhalb der Tabs fest. */
        for (ContentSource source : ContentSources.all()) CreativeTabs.loadDefinitions(source.creativeTabs());
        CreativeTabs.build();
    }

    private static void registerFood(String id, int nutrition, float saturation, String texture) {
        Identifier i = Identifier.of(id);
        if (!Registries.ITEM.contains(i)) {
            Registries.ITEM.register(i, new FoodItem(i, nutrition, saturation, texture));
        }
        CreativeTabs.assign(i, "food");
        /* Item-Textur in den Block-Atlas aufnehmen (vor dem TextureArray-Bau, wie bei den Eimern). */
        BlockTextures.layerOf(texture);
    }

    private static void registerBucket(String id, Block fluid, String texture, int maxStackSize) {
        Identifier i = Identifier.of(id);
        if (!Registries.ITEM.contains(i)) {
            Registries.ITEM.register(i, new BucketItem(i, fluid, texture, maxStackSize));
        }
        CreativeTabs.assign(i, "tools");
        /* Item-Textur in den Block-Atlas aufnehmen (vor dem TextureArray-Bau in ChunkRenderer.init). */
        BlockTextures.layerOf(texture);
    }

    public static Item get(Identifier id) {
        return Registries.ITEM.get(id);
    }

    /** Merkt das platzierende Item eines no_item-Blocks vor (Aufrufer: ItemLoader). */
    public static void registerPlacer(Identifier blockId, Item item) {
        PLACER_BY_BLOCK.putIfAbsent(blockId, item);
    }

    /**
     * Das Item zu einem Block — für Drops und Pick-Block. Normalfall ist das gleichnamige
     * Auto-BlockItem; für no_item-Blöcke (Staub) löst die places_block-Rückwärts-Zuordnung
     * auf. null, wenn es keins gibt (dann droppt nichts, wie bisher).
     */
    public static Item forBlock(Block block) {
        Item placer = PLACER_BY_BLOCK.get(block.getIdentifier());
        if (placer != null) return placer;
        return Registries.ITEM.get(block.getIdentifier());
    }

    private Items() {}
}
