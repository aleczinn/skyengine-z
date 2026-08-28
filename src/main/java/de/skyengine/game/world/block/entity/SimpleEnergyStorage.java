package de.skyengine.game.world.block.entity;

/** Einfacher Energiespeicher mit Kapazität und Transferlimit pro Vorgang. */
public final class SimpleEnergyStorage implements EnergyStorage {

    private long energy;
    private final long capacity;
    private final long maxReceive;
    private final long maxExtract;
    private final Runnable changeListener;

    public SimpleEnergyStorage(long capacity, long maxIO) {
        this(capacity, maxIO, maxIO, () -> {});
    }

    public SimpleEnergyStorage(long capacity, long maxReceive, long maxExtract) {
        this(capacity, maxReceive, maxExtract, () -> {});
    }

    public SimpleEnergyStorage(long capacity, long maxReceive, long maxExtract, Runnable changeListener) {
        if (capacity < 0 || maxReceive < 0 || maxExtract < 0) {
            throw new IllegalArgumentException("Energy limits must be non-negative");
        }
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
        this.changeListener = changeListener == null ? () -> {} : changeListener;
    }

    @Override
    public long receive(long maxReceive, boolean simulate) {
        if (maxReceive <= 0) return 0;
        long accepted = Math.min(Math.min(maxReceive, this.maxReceive), this.capacity - this.energy);
        if (accepted > 0 && !simulate) {
            this.energy += accepted;
            this.changeListener.run();
        }
        return Math.max(0, accepted);
    }

    @Override
    public long extract(long maxExtract, boolean simulate) {
        if (maxExtract <= 0) return 0;
        long removed = Math.min(Math.min(maxExtract, this.maxExtract), this.energy);
        if (removed > 0 && !simulate) {
            this.energy -= removed;
            this.changeListener.run();
        }
        return Math.max(0, removed);
    }

    @Override public long getEnergy() { return energy; }
    @Override public long getCapacity() { return capacity; }
    @Override public long getMaxReceive() { return maxReceive; }
    @Override public long getMaxExtract() { return maxExtract; }

    public void setEnergy(long energy) {
        long clamped = Math.max(0, Math.min(this.capacity, energy));
        if (this.energy != clamped) {
            this.energy = clamped;
            this.changeListener.run();
        }
    }
}
