package de.skyengine.game.world.block.entity;

/**
 * Mitgelieferte Capability-Schlüssel. Weitere (ITEM_STORAGE, FLUID_STORAGE) folgen mit den
 * jeweiligen Subsystemen. Mods registrieren eigene Capabilities analog.
 */
public final class Capabilities {

    public static final Capability<EnergyStorage> ENERGY = new Capability<>("energy");

    private Capabilities() {}
}
