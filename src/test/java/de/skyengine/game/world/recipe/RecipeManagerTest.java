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

    @Test void chestRecipeAcceptsEveryPlankTypeAndMixedPlanks() {
        List<String> plankTypes = List.of(
                "oak_planks", "birch_planks", "spruce_planks", "dark_oak_planks",
                "acacia_planks", "jungle_planks", "mangrove_planks", "pale_oak_planks");

        for (String plankType : plankTypes) {
            ArrayGrid grid = chestGrid(java.util.Collections.nCopies(8, plankType));
            CraftingRecipe recipe = RecipeManager.get().findCrafting(RecipeManager.CRAFTING, grid);
            assertNotNull(recipe, () -> "Chest recipe missing for " + plankType);
            assertEquals(item("chest"), recipe.result().getItem());
        }

        CraftingRecipe mixedRecipe = RecipeManager.get().findCrafting(
                RecipeManager.CRAFTING, chestGrid(plankTypes));
        assertNotNull(mixedRecipe, "Chest recipe must accept mixed plank types");
        assertEquals(item("chest"), mixedRecipe.result().getItem());
    }

    @Test void fuelLookupIsConstantMapResult() {
        assertEquals(1600, RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL,
                new ItemStack(item("coal"), 1)));
        assertEquals(20_000, burnTime("lava_bucket"));
        assertEquals(item("bucket"), item("lava_bucket").getCraftingRemainder());
        assertEquals(300, burnTime("oak_log"));
        assertEquals(300, burnTime("stripped_spruce_log"));
        assertEquals(300, burnTime("oak_planks"));
        assertEquals(300, burnTime("oak_stairs"));
        assertEquals(150, burnTime("oak_slab"));
        assertEquals(200, burnTime("oak_door"));
        assertEquals(200, burnTime("wooden_pickaxe"));
        assertEquals(100, burnTime("stick"));
        assertEquals(100, burnTime("oak_sapling"));
        assertEquals(100, burnTime("white_wool"));
        assertEquals(67, burnTime("white_carpet"));
        assertTrue(RecipeManager.get().fuels().burnTime(Identifier.of("test:other"),
                new ItemStack(item("coal"), 1)) == 0);
    }

    private static int burnTime(String id) {
        return RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL,
                new ItemStack(item(id), 1));
    }

    private static ArrayGrid chestGrid(List<String> plankTypes) {
        ArrayGrid grid = new ArrayGrid(3, 3);
        int plankIndex = 0;
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                if (x == 1 && y == 1) continue;
                grid.set(x, y, item(plankTypes.get(plankIndex++)));
            }
        }
        return grid;
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

    private static Item item(String id) { return Items.get(Identifier.of("voxelstories:" + id)); }

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
