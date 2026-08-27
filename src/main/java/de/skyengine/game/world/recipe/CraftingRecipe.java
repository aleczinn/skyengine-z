package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.ItemStack;

public interface CraftingRecipe {
    Identifier id();
    Identifier recipeType();
    ItemStack result();
    int priority();
    long loadOrder();
    boolean matches(CraftingGrid grid);
}
