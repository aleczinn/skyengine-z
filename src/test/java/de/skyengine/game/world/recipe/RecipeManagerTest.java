package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecipeManagerTest {

    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test void shapedRecipeMayBeMovedInsideLargerGrid() {
        ArrayGrid grid = new ArrayGrid(3, 3);
        Item planks = item("oak_planks");
        grid.set(1, 1, planks);
        grid.set(2, 1, planks);
        grid.set(1, 2, planks);
        grid.set(2, 2, planks);

        CraftingRecipe recipe = RecipeManager.get().findCrafting(RecipeManager.CRAFTING, grid);

        assertNotNull(recipe);
        assertEquals(item("crafting_table"), recipe.result().getItem());
    }

    @Test void twoByTwoGridRejectsThreeByThreeFurnacePattern() {
        ArrayGrid grid = new ArrayGrid(2, 2);
        Item cobble = item("cobblestone");
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) grid.set(x, y, cobble);
        assertNull(RecipeManager.get().findCrafting(RecipeManager.CRAFTING, grid));
    }

    @Test void shapelessLogRecipeUsesRegisteredContent() {
        ArrayGrid grid = new ArrayGrid(9, 9);
        grid.set(8, 8, item("birch_log"));
        CraftingRecipe recipe = RecipeManager.get().findCrafting(RecipeManager.CRAFTING, grid);
        assertNotNull(recipe);
        assertEquals(item("birch_planks"), recipe.result().getItem());
        assertEquals(4, recipe.result().getCount());
    }

    @Test void tagIngredientAcceptsCoalAndCharcoal() {
        for (String coal : List.of("coal", "charcoal")) {
            ArrayGrid grid = new ArrayGrid(2, 2);
            grid.set(0, 0, item(coal));
            grid.set(0, 1, item("stick"));
            CraftingRecipe recipe = RecipeManager.get().findCrafting(RecipeManager.CRAFTING, grid);
            assertNotNull(recipe);
            assertEquals(item("torch"), recipe.result().getItem());
        }
    }

    @Test void fuelLookupIsConstantMapResult() {
        assertEquals(1600, RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL,
                new ItemStack(item("coal"), 1)));
        assertTrue(RecipeManager.get().fuels().burnTime(Identifier.of("test:other"),
                new ItemStack(item("coal"), 1)) == 0);
    }

    @Test void indexDoesNotScanOneHundredThousandIrrelevantRecipes() {
        RecipeManager manager = new RecipeManager();
        Map<Identifier, CraftingRecipe> recipes = new LinkedHashMap<>();
        Item output = new Item(Identifier.of("perf:output"));
        Item wanted = null;
        for (int i = 0; i < 100_000; i++) {
            Item ingredientItem = new Item(Identifier.of("perf:ingredient_" + i));
            if (i == 54_321) wanted = ingredientItem;
            recipes.put(Identifier.of("perf:recipe_" + i), new ShapedCraftingRecipe(
                    Identifier.of("perf:recipe_" + i), RecipeManager.CRAFTING, 1, 1,
                    List.of(new Ingredient(Set.of(ingredientItem))), new ItemStack(output, 1), 0, i));
        }
        manager.build(recipes, Map.of(), List.of());
        ArrayGrid grid = new ArrayGrid(9, 9);
        grid.set(8, 8, wanted);

        assertNotNull(manager.findCrafting(RecipeManager.CRAFTING, grid));
        assertEquals(1, manager.lastCandidateCount());
    }

    private static Item item(String id) { return Items.get(Identifier.of("skyengine:" + id)); }

    private static final class ArrayGrid implements CraftingGrid {
        private final int width, height;
        private final ItemStack[] stacks;
        ArrayGrid(int width, int height) {
            this.width = width; this.height = height;
            this.stacks = new ItemStack[width * height];
            java.util.Arrays.fill(this.stacks, ItemStack.EMPTY);
        }
        void set(int x, int y, Item item) { this.stacks[y * this.width + x] = new ItemStack(item, 1); }
        @Override public int width() { return this.width; }
        @Override public int height() { return this.height; }
        @Override public ItemStack get(int x, int y) { return this.stacks[y * this.width + x]; }
    }
}
