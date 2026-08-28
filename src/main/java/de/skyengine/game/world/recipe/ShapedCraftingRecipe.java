package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.ItemStack;

import java.util.List;

public record ShapedCraftingRecipe(Identifier id, Identifier recipeType, int width, int height,
                                   List<Ingredient> ingredients, ItemStack result,
                                   int priority, long loadOrder) implements CraftingRecipe {

    public ShapedCraftingRecipe {
        ingredients = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(ingredients));
        result = result.copy();
        if (width < 1 || height < 1 || width > 9 || height > 9
                || ingredients.size() != width * height) {
            throw new IllegalArgumentException("Ungueltige Rezeptgroesse " + width + "x" + height);
        }
    }

    @Override public ItemStack result() { return this.result.copy(); }

    @Override
    public boolean matches(CraftingGrid grid) {
        NormalizedGrid normalized = NormalizedGrid.of(grid);
        if (normalized.width() != this.width || normalized.height() != this.height) return false;
        return matches(normalized, false) || matches(normalized, true);
    }

    boolean matches(NormalizedGrid grid, boolean mirrored) {
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                int patternX = mirrored ? this.width - 1 - x : x;
                Ingredient ingredient = this.ingredients.get(y * this.width + patternX);
                ItemStack stack = grid.get(x, y);
                if (ingredient == null ? !stack.isEmpty() : !ingredient.test(stack)) return false;
            }
        }
        return true;
    }

    String occupancy() {
        StringBuilder out = new StringBuilder(this.ingredients.size());
        for (Ingredient ingredient : this.ingredients) out.append(ingredient == null ? '0' : '1');
        return out.toString();
    }
}
