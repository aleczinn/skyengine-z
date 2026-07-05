package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.registry.Registries;

/**
 * Item-Registry-Zugriff + Bootstrap. Jeder Nicht-Luft-Block bekommt automatisch ein
 * {@link BlockItem} mit derselben {@link Identifier} - so lässt sich ein Block als Item
 * lagern/halten. Muss NACH der Block-Registrierung laufen.
 */
public final class Items {

    public static void bootstrap() {
        for (Block block : Registries.BLOCK.values()) {
            if (block.isAir()) continue;
            /* Fluids (Wasser/Lava) bekommen kein Block-Item — sie werden nur über Eimer gehandhabt. */
            if (block.isFluid()) continue;
            Identifier id = block.getIdentifier();
            if (!Registries.ITEM.contains(id)) {
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
                BlockTextures.layerOf(texture); // vor dem TextureArray-Bau registrieren
            }
        }

        /* Eimer sind eigenständige Items (keine Block-Items). Leer stapelt wie in MC bis 16. */
        Block water = Registries.BLOCK.get(Identifier.of("skyengine:water"));
        Block lava = Registries.BLOCK.get(Identifier.of("skyengine:lava"));
        registerBucket("skyengine:bucket", null, "game/textures/item/bucket.png", 16);
        registerBucket("skyengine:water_bucket", water, "game/textures/item/water_bucket.png", 1);
        registerBucket("skyengine:lava_bucket", lava, "game/textures/item/lava_bucket.png", 1);
    }

    private static void registerBucket(String id, Block fluid, String texture, int maxStackSize) {
        Identifier i = Identifier.of(id);
        if (!Registries.ITEM.contains(i)) {
            Registries.ITEM.register(i, new BucketItem(i, fluid, texture, maxStackSize));
        }
        /* Item-Textur in den Block-Atlas aufnehmen (vor dem TextureArray-Bau in ChunkRenderer.init). */
        BlockTextures.layerOf(texture);
    }

    public static Item get(Identifier id) {
        return Registries.ITEM.get(id);
    }

    private Items() {}
}
