package de.skyengine.game.world.block.entity;

/**
 * Typisierter Zugriffspunkt auf eine BlockEntity-Fähigkeit (Energie, Item-Lager, Fluid-Tank).
 * Entkoppelt Subsysteme (Pipes/Cables/Netzwerke) von konkreten BlockEntity-Klassen — der
 * zentrale Modding-Hook für die Modpack-Features. Konkrete Capabilities (ENERGY, ITEM_STORAGE,
 * FLUID_STORAGE) kommen mit dem Netzwerk-/Maschinen-Subsystem (Phase 4g).
 *
 * @param <C> Schnittstellentyp der Fähigkeit (z.B. EnergyStorage)
 */
public final class Capability<C> {

    private final String name;

    public Capability(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
