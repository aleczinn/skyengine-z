package de.skyengine.game.world.block.entity;

/** Einfacher Energiespeicher mit Kapazität und Transferlimit pro Vorgang. */
public final class SimpleEnergyStorage implements EnergyStorage {

    private int energy;
    private final int capacity;
    private final int maxIO;

    public SimpleEnergyStorage(int capacity, int maxIO) {
        this.capacity = capacity;
        this.maxIO = maxIO;
    }

    @Override
    public int receive(int maxReceive, boolean simulate) {
        int accepted = Math.min(Math.min(maxReceive, this.maxIO), this.capacity - this.energy);
        if (accepted > 0 && !simulate) this.energy += accepted;
        return Math.max(0, accepted);
    }

    @Override
    public int extract(int maxExtract, boolean simulate) {
        int removed = Math.min(Math.min(maxExtract, this.maxIO), this.energy);
        if (removed > 0 && !simulate) this.energy -= removed;
        return Math.max(0, removed);
    }

    @Override public int getEnergy() { return energy; }
    @Override public int getCapacity() { return capacity; }

    public void setEnergy(int energy) { this.energy = Math.max(0, Math.min(this.capacity, energy)); }
}
