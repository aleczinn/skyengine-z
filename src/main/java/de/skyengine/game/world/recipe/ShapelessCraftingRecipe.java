package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record ShapelessCraftingRecipe(Identifier id, Identifier recipeType, List<Ingredient> ingredients,
                                      ItemStack result, int priority, long loadOrder) implements CraftingRecipe {
    public ShapelessCraftingRecipe {
        ingredients = List.copyOf(ingredients);
        result = result.copy();
        if (ingredients.isEmpty() || ingredients.size() > 81) throw new IllegalArgumentException("Ungueltige Zutatenzahl");
    }

    @Override public ItemStack result() { return this.result.copy(); }

    @Override
    public boolean matches(CraftingGrid grid) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int y = 0; y < grid.height(); y++) for (int x = 0; x < grid.width(); x++) {
            ItemStack stack = grid.get(x, y);
            if (!stack.isEmpty()) stacks.add(stack);
        }
        if (stacks.size() != this.ingredients.size()) return false;
        List<Ingredient> sorted = new ArrayList<>(this.ingredients);
        sorted.sort(Comparator.comparingInt(value -> value.acceptedItems().size()));
        return assign(sorted, stacks, 0, new boolean[stacks.size()]);
    }

    private static boolean assign(List<Ingredient> ingredients, List<ItemStack> stacks, int index, boolean[] used) {
        if (index == ingredients.size()) return true;
        Ingredient ingredient = ingredients.get(index);
        for (int i = 0; i < stacks.size(); i++) {
            if (used[i] || !ingredient.test(stacks.get(i))) continue;
            used[i] = true;
            if (assign(ingredients, stacks, index + 1, used)) return true;
            used[i] = false;
        }
        return false;
    }
}
