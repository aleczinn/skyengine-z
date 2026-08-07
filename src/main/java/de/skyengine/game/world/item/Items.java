package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.block.content.ContentSources;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.item.json.ItemLoader;

/** Item-Registry-Zugriff und Bootstrap nach dem Block-Bake. */
public final class Items {

    /** Rueckwaerts-Zuordnung fuer no_item-Bloecke mit fremdem places_block-Item. */
    private static final java.util.Map<Identifier, Item> PLACER_BY_BLOCK = new java.util.HashMap<>();

    public static void bootstrap() {
        PLACER_BY_BLOCK.clear();
        for (Block block : Registries.BLOCK.values()) {
            if (block.isAir() || block.isFluid()) continue;
            Identifier id = block.getIdentifier();
            if (!block.hasNoItem() && !Registries.ITEM.contains(id)) {
                Registries.ITEM.register(id, new BlockItem(block));
            }

            /* Auch BlockItem-Icons muessen vor dem TextureArray-Bau registriert sein. */
            String iconItem = BlockStateModels.iconItem(block);
            if (iconItem != null) BlockTextures.layerOf(iconItem);
            String[] iconFlat = BlockStateModels.flatIcon(block);
            if (iconFlat != null) {
                for (String path : iconFlat) BlockTextures.layerOf(path);
            }
        }

        /* Alle eigenstaendigen Items werden mitsamt Archetyp und Texturen aus JSON erzeugt. */
        for (ContentSource source : ContentSources.all()) ItemLoader.load(source.items());

        for (ContentSource source : ContentSources.all()) CreativeTabs.loadDefinitions(source.creativeTabs());
        CreativeTabs.build();
    }

    public static Item get(Identifier id) {
        return Registries.ITEM.get(id);
    }

    public static void registerPlacer(Identifier blockId, Item item) {
        PLACER_BY_BLOCK.putIfAbsent(blockId, item);
    }

    public static Item forBlock(Block block) {
        Item placer = PLACER_BY_BLOCK.get(block.getIdentifier());
        return placer != null ? placer : Registries.ITEM.get(block.getIdentifier());
    }

    private Items() {}
}
