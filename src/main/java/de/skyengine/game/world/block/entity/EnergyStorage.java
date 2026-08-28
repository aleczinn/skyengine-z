package de.skyengine.game.world.block.entity;

/**
 * Energie-Fähigkeit (Forge-artig). Maschinen/Speicher/Kabel implementieren sie; Pipes/Cables
 * und Netzwerke greifen ausschließlich darüber zu — entkoppelt von konkreten Klassen.
 */
public interface EnergyStorage {

    /** Nimmt bis zu maxReceive auf; liefert die tatsächlich aufgenommene Menge. */
    long receive(long maxReceive, boolean simulate);

    /** Entnimmt bis zu maxExtract; liefert die tatsächlich entnommene Menge. */
    long extract(long maxExtract, boolean simulate);

    long getEnergy();

    long getCapacity();

    long getMaxReceive();

    long getMaxExtract();

    default boolean canReceive() { return getMaxReceive() > 0; }

    default boolean canExtract() { return getMaxExtract() > 0; }
}
