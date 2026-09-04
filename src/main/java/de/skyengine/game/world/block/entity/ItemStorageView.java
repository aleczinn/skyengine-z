package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.item.ItemStack;

/** Contiguous non-owning slot view used by replicated compound containers. */
public final class ItemStorageView implements ItemStorage {
    private final ItemStorage storage;
    private final int offset;
    private final int size;

    public ItemStorageView(ItemStorage storage, int offset, int size) {
        if (storage == null || offset < 0 || size < 0 || offset + size > storage.size()) {
            throw new IllegalArgumentException("Invalid item storage view");
        }
        this.storage = storage;
        this.offset = offset;
        this.size = size;
    }

    @Override public int size() { return this.size; }
    @Override public ItemStack get(int slot) { return this.storage.get(index(slot)); }
    @Override public void set(int slot, ItemStack stack) { this.storage.set(index(slot), stack); }
    @Override public ItemStack extract(int slot, int amount) { return this.storage.extract(index(slot), amount); }
    @Override public void setChanged() { this.storage.setChanged(); }

    @Override
    public ItemStack insert(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < this.size && !remaining.isEmpty(); slot++) {
            ItemStack existing = get(slot);
            if (!existing.canStackWith(remaining)) continue;
            int moved = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            if (moved <= 0) continue;
            existing.setCount(existing.getCount() + moved);
            remaining.setCount(remaining.getCount() - moved);
        }
        for (int slot = 0; slot < this.size && !remaining.isEmpty(); slot++) {
            if (!get(slot).isEmpty()) continue;
            set(slot, remaining.split(remaining.getMaxStackSize()));
        }
        setChanged();
        return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
    }

    private int index(int slot) {
        if (slot < 0 || slot >= this.size) throw new IndexOutOfBoundsException(slot);
        return this.offset + slot;
    }
}
