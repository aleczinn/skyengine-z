package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.item.ItemStack;

/**
 * Item-Lager-Fähigkeit (Truhe, Maschinen-Slots, Hopper). Zugriff entkoppelt über die
 * {@link Capabilities#ITEM_STORAGE}-Capability - genau wie {@link EnergyStorage} für Energie.
 * Automatisierung (Hopper/Pipes) greift später ausschließlich hierüber zu.
 */
public interface ItemStorage {

    int size();

    ItemStack get(int slot);

    void set(int slot, ItemStack stack);

    /** Fügt so viel wie möglich ein; liefert den nicht passenden Rest ({@link ItemStack#EMPTY} = alles rein). */
    ItemStack insert(ItemStack stack);

    /** Entnimmt bis zu {@code amount} aus einem Slot; liefert den entnommenen Stapel. */
    ItemStack extract(int slot, int amount);
}
