package de.skyengine.game.world.loot;

import de.skyengine.game.world.item.ItemStack;

@FunctionalInterface
public interface LootSink {
    void accept(ItemStack stack, int x, int y, int z);
}
