package de.skyengine.game.world.block.entity;

/**
 * Energie-Fähigkeit (Forge-artig). Maschinen/Speicher/Kabel implementieren sie; Pipes/Cables
 * und Netzwerke greifen ausschließlich darüber zu — entkoppelt von konkreten Klassen.
 */
public interface EnergyStorage {

    /** Nimmt bis zu maxReceive auf; liefert die tatsächlich aufgenommene Menge. */
    int receive(int maxReceive, boolean simulate);

    /** Entnimmt bis zu maxExtract; liefert die tatsächlich entnommene Menge. */
    int extract(int maxExtract, boolean simulate);

    int getEnergy();

    int getCapacity();
}
