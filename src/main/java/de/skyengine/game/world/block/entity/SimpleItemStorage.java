package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.item.ItemStack;

import java.util.Arrays;

/** Slot-basiertes Item-Lager fester Größe (z.B. Truhe = 27). Analog zu {@link SimpleEnergyStorage}. */
public final class SimpleItemStorage implements ItemStorage {

    private final ItemStack[] slots;
    private final Runnable changeListener;

    public SimpleItemStorage(int size) {
        this(size, () -> {});
    }

    public SimpleItemStorage(int size, Runnable changeListener) {
        this.slots = new ItemStack[size];
        this.changeListener = changeListener;
        Arrays.fill(this.slots, ItemStack.EMPTY);
    }

    @Override
    public int size() {
        return this.slots.length;
    }

    @Override
    public ItemStack get(int slot) {
        return this.slots[slot];
    }

    @Override
    public void set(int slot, ItemStack stack) {
        this.slots[slot] = stack == null ? ItemStack.EMPTY : stack;
        this.setChanged();
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();

        /* 1) In vorhandene gleiche Stapel auffüllen. */
        for (int i = 0; i < this.slots.length && !remaining.isEmpty(); i++) {
            ItemStack slot = this.slots[i];
            if (!slot.canStackWith(remaining)) continue;
            int space = slot.getMaxStackSize() - slot.getCount();
            if (space <= 0) continue;
            int moved = Math.min(space, remaining.getCount());
            slot.setCount(slot.getCount() + moved);
            remaining.setCount(remaining.getCount() - moved);
        }
        /* 2) In leere Slots ablegen (split zieht selbst ab und erhält die Abnutzung). */
        for (int i = 0; i < this.slots.length && !remaining.isEmpty(); i++) {
            if (!this.slots[i].isEmpty()) continue;
            this.slots[i] = remaining.split(remaining.getMaxStackSize());
        }
        if (remaining.getCount() != stack.getCount()) this.setChanged();
        return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
    }

    @Override
    public ItemStack extract(int slot, int amount) {
        ItemStack out = this.slots[slot].split(amount);
        if (this.slots[slot].isEmpty()) this.slots[slot] = ItemStack.EMPTY;
        if (!out.isEmpty()) this.setChanged();
        return out;
    }

    @Override
    public void setChanged() {
        this.changeListener.run();
    }

    /* --- Persistenz: nur belegte Slots schreiben. --- */

    public void save(DataTag tag) {
        tag.putInt("size", this.slots.length);
        for (int i = 0; i < this.slots.length; i++) {
            if (!this.slots[i].isEmpty()) tag.putTag("slot" + i, this.slots[i].save());
        }
    }

    public void load(DataTag tag) {
        if (tag == null) return;
        for (int i = 0; i < this.slots.length; i++) {
            DataTag slotTag = tag.getTag("slot" + i);
            this.slots[i] = slotTag != null ? ItemStack.load(slotTag) : ItemStack.EMPTY;
        }
    }
}
