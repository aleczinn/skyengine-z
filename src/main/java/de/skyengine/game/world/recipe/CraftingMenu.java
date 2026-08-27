package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;

import java.util.function.Consumer;

/** Temporäres Crafting-Raster samt virtuellem Ergebnis-Slot und atomarem Zutatenverbrauch. */
public final class CraftingMenu implements CraftingGrid {

    private final int width;
    private final int height;
    private final Identifier recipeType;
    private final ItemStorage playerInventory;
    private final Consumer<ItemStack> overflow;
    private final SimpleItemStorage input;
    private final ItemStorage output = new ResultStorage();
    private CraftingRecipe recipe;
    private ItemStack result = ItemStack.EMPTY;

    public CraftingMenu(int width, int height, Identifier recipeType,
                        ItemStorage playerInventory, Consumer<ItemStack> overflow) {
        if (width < 1 || height < 1 || width > 9 || height > 9) {
            throw new IllegalArgumentException("Crafting-Raster ausserhalb 1..9: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.recipeType = recipeType;
        this.playerInventory = playerInventory;
        this.overflow = overflow;
        this.input = new SimpleItemStorage(width * height, this::refresh);
        this.refresh();
    }

    public ItemStorage input() { return this.input; }
    public ItemStorage output() { return this.output; }
    @Override public int width() { return this.width; }
    @Override public int height() { return this.height; }
    @Override public ItemStack get(int x, int y) { return this.input.get(y * this.width + x); }

    public void refresh() {
        this.recipe = RecipeManager.get().findCrafting(this.recipeType, this);
        this.result = this.recipe == null ? ItemStack.EMPTY : this.recipe.result();
    }

    /** Shift-Klick: fertigt so oft, wie das Spielerinventar die komplette Ausgabe aufnehmen kann. */
    public int craftAll() {
        int crafts = 0;
        while (!this.result.isEmpty() && canFullyInsert(this.playerInventory, this.result)) {
            ItemStack made = this.takeResult();
            if (made.isEmpty()) break;
            ItemStack rest = this.playerInventory.insert(made);
            if (!rest.isEmpty()) this.overflow.accept(rest);
            crafts++;
        }
        return crafts;
    }

    public void close() {
        for (int slot = 0; slot < this.input.size(); slot++) {
            ItemStack stack = this.input.extract(slot, Integer.MAX_VALUE);
            if (stack.isEmpty()) continue;
            ItemStack rest = this.playerInventory.insert(stack);
            if (!rest.isEmpty()) this.overflow.accept(rest);
        }
    }

    private ItemStack takeResult() {
        if (this.recipe == null || this.result.isEmpty()) return ItemStack.EMPTY;
        ItemStack made = this.result.copy();
        for (int slot = 0; slot < this.input.size(); slot++) {
            ItemStack stack = this.input.get(slot);
            if (stack.isEmpty()) continue;
            Item consumedItem = stack.getItem();
            this.input.extract(slot, 1);
            Item remainder = consumedItem.getCraftingRemainder();
            if (remainder == null) continue;
            ItemStack remainderStack = new ItemStack(remainder, 1);
            if (this.input.get(slot).isEmpty()) this.input.set(slot, remainderStack);
            else {
                ItemStack rest = this.playerInventory.insert(remainderStack);
                if (!rest.isEmpty()) this.overflow.accept(rest);
            }
        }
        this.refresh();
        return made;
    }

    private static boolean canFullyInsert(ItemStorage inventory, ItemStack stack) {
        int remaining = stack.getCount();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack existing = inventory.get(i);
            if (existing.isEmpty()) remaining -= stack.getMaxStackSize();
            else if (existing.canStackWith(stack)) remaining -= existing.getMaxStackSize() - existing.getCount();
            if (remaining <= 0) return true;
        }
        return false;
    }

    private final class ResultStorage implements ItemStorage {
        @Override public int size() { return 1; }
        @Override public ItemStack get(int slot) { return CraftingMenu.this.result.copy(); }
        @Override public void set(int slot, ItemStack stack) { }
        @Override public ItemStack insert(ItemStack stack) { return stack == null ? ItemStack.EMPTY : stack; }
        @Override public ItemStack extract(int slot, int amount) {
            return amount <= 0 ? ItemStack.EMPTY : CraftingMenu.this.takeResult();
        }
    }
}
