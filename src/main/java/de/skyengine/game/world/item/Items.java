package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
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
            Identifier id = block.getIdentifier();
            if (!Registries.ITEM.contains(id)) {
                Registries.ITEM.register(id, new BlockItem(block));
            }
        }
    }

    public static Item get(Identifier id) {
        return Registries.ITEM.get(id);
    }

    private Items() {}
}
