package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CraftingMenuTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test void takingResultConsumesOneItemPerOccupiedSlot() {
        SimpleItemStorage player = new SimpleItemStorage(36);
        CraftingMenu menu = new CraftingMenu(2, 2, RecipeManager.CRAFTING, player, ignored -> {});
        for (int slot = 0; slot < 4; slot++) menu.input().set(slot, new ItemStack(item("oak_planks"), 2));

        ItemStack result = menu.output().extract(0, 64);

        assertEquals(item("crafting_table"), result.getItem());
        for (int slot = 0; slot < 4; slot++) assertEquals(1, menu.input().get(slot).getCount());
    }

    @Test void shiftCraftStopsWhenIngredientsRunOut() {
        SimpleItemStorage player = new SimpleItemStorage(36);
        CraftingMenu menu = new CraftingMenu(2, 2, RecipeManager.CRAFTING, player, ignored -> {});
        menu.input().set(0, new ItemStack(item("oak_planks"), 3));
        menu.input().set(2, new ItemStack(item("birch_planks"), 3));

        assertEquals(3, menu.craftAll());
        assertEquals(12, count(player, item("stick")));
        assertTrue(menu.output().get(0).isEmpty());
    }

    @Test void closingReturnsInputAndReportsOnlyOverflow() {
        SimpleItemStorage player = new SimpleItemStorage(1);
        player.set(0, new ItemStack(item("stone"), 64));
        List<ItemStack> overflow = new ArrayList<>();
        CraftingMenu menu = new CraftingMenu(2, 2, RecipeManager.CRAFTING, player, overflow::add);
        menu.input().set(0, new ItemStack(item("oak_planks"), 5));

        menu.close();

        assertEquals(1, overflow.size());
        assertEquals(5, overflow.getFirst().getCount());
    }

    private static int count(SimpleItemStorage storage, Item item) {
        int count = 0;
        for (int i = 0; i < storage.size(); i++) if (storage.get(i).getItem() == item) count += storage.get(i).getCount();
        return count;
    }
    private static Item item(String id) { return Items.get(Identifier.of("skyengine:" + id)); }
}
