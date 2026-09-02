package de.skyengine.shared.entity;

/** Kleine, zustandslose Entity-Ereignisse zusaetzlich zu den regelmaessigen Snapshots. */
public final class EntityEventTypes {
    public static final int SWING = 1;
    public static final int HURT = 2;
    public static final int PICKUP = 3;

    private EntityEventTypes() { }
}
