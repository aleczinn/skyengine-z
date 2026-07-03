package de.skyengine.game.world.block.registry;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.archetype.Archetype;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.item.Item;

/**
 * Zentrale Registry-Instanzen. Single Source of Truth für alle registrierbaren Inhalte.
 */
public final class Registries {

    public static final Registry<Archetype> BLOCK_ARCHETYPE = new Registry<>("block_archetype");
    public static final Registry<Block> BLOCK = new Registry<>("block");
    public static final Registry<BlockEntityType<?>> BLOCK_ENTITY = new Registry<>("block_entity");
    public static final Registry<Item> ITEM = new Registry<>("item");

    private Registries() {}
}