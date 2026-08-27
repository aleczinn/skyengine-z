package de.skyengine.game.world.recipe;

import de.skyengine.game.world.item.ItemStack;

/** Lesesicht auf ein rechteckiges Crafting-Raster. */
public interface CraftingGrid {
    int width();
    int height();
    ItemStack get(int x, int y);
}
