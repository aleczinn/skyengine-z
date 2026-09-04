package de.skyengine.shared.gameplay;

/** Stable protocol-side layout type; gameplay implementations remain server-owned. */
public enum ContainerKind {
    CHEST,
    HOPPER,
    DISPENSER,
    FURNACE,
    CRAFTING,
    PLAYER_INVENTORY
}
