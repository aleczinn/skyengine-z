package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.item.ItemStack;

/**
 * Gemeinsame Sicht auf zwei Inventare. Entspricht Vanillas {@code CompoundContainer}, das bei
 * einer Doppeltruhe 54 fortlaufende Slots anbietet und bei {@code setChanged} beide Hälften
 * benachrichtigt.
 */
public final class CompoundItemStorage implements ItemStorage {

    private final ItemStorage first;
    private final ItemStorage second;

    public CompoundItemStorage(ItemStorage first, ItemStorage second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public int size() {
        return this.first.size() + this.second.size();
    }

    @Override
    public ItemStack get(int slot) {
        return this.storage(slot).get(this.localSlot(slot));
    }

    @Override
    public void set(int slot, ItemStack stack) {
        this.storage(slot).set(this.localSlot(slot), stack);
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        ItemStack remaining = this.first.insert(stack);
        if (!remaining.isEmpty()) remaining = this.second.insert(remaining);
        return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
    }

    @Override
    public ItemStack extract(int slot, int amount) {
        return this.storage(slot).extract(this.localSlot(slot), amount);
    }

    @Override
    public void setChanged() {
        this.first.setChanged();
        this.second.setChanged();
    }

    private ItemStorage storage(int slot) {
        this.checkSlot(slot);
        return slot < this.first.size() ? this.first : this.second;
    }

    private int localSlot(int slot) {
        return slot < this.first.size() ? slot : slot - this.first.size();
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= this.size()) {
            throw new IndexOutOfBoundsException("slot " + slot + " outside 0.." + (this.size() - 1));
        }
    }
}
