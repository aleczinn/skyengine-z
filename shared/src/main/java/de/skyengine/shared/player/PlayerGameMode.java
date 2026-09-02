package de.skyengine.shared.player;

/** Server-authoritative gameplay mode shared without depending on client gameplay classes. */
public enum PlayerGameMode {
    SURVIVAL(false, false),
    CREATIVE(true, false),
    SPECTATOR(true, true);

    private final boolean canFly;
    private final boolean alwaysFly;

    PlayerGameMode(boolean canFly, boolean alwaysFly) {
        this.canFly = canFly;
        this.alwaysFly = alwaysFly;
    }

    public boolean canFly() { return this.canFly; }
    public boolean alwaysFly() { return this.alwaysFly; }
    public PlayerGameMode next() { return values()[(ordinal() + 1) % values().length]; }
}
