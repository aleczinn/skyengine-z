package de.skyengine.game;

/**
 * Spielmodi nach Minecraft-Vorbild. Jeder Modus trägt seine Verhaltens-Flags direkt
 * (Muster wie {@link de.skyengine.game.world.block.Direction}).
 *
 * <p>Reihenfolge bestimmt den Zyklus von {@link #next()}:
 * Survival → Creative → Spectator → Survival.
 */
public enum Gamemode {
    /** Laufen, Schwerkraft, Blöcke droppen, kann abbauen/platzieren/nutzen; Abbau nach Härte/Tool. */
    SURVIVAL(false, false, true, true, false),
    /** Fliegen erlaubt (per F), keine Drops, kann abbauen/platzieren/nutzen; Abbau instant. */
    CREATIVE(true, false, false, true, true),
    /** Dauerhaft Fliegen + NoClip, kann nicht abbauen/platzieren/interagieren. */
    SPECTATOR(true, true, false, false, true);

    private final boolean canFly;             // darf Fliegen umschalten
    private final boolean alwaysFly;          // erzwingt Fliegen + NoClip (Spectator)
    private final boolean dropsItems;         // Block-Abbau droppt Item
    private final boolean interactsWithWorld; // darf abbauen/platzieren/nutzen
    private final boolean instantBreak;       // Abbau sofort (Creative) statt nach Härte/Tool

    Gamemode(boolean canFly, boolean alwaysFly, boolean dropsItems, boolean interactsWithWorld, boolean instantBreak) {
        this.canFly = canFly;
        this.alwaysFly = alwaysFly;
        this.dropsItems = dropsItems;
        this.interactsWithWorld = interactsWithWorld;
        this.instantBreak = instantBreak;
    }

    public boolean canFly() {
        return canFly;
    }

    public boolean isAlwaysFly() {
        return alwaysFly;
    }

    public boolean dropsItems() {
        return dropsItems;
    }

    public boolean interactsWithWorld() {
        return interactsWithWorld;
    }

    /** Abbau sofort (Creative) statt zeitbasiert nach Härte/Tool (Survival). */
    public boolean isInstantBreak() {
        return instantBreak;
    }

    /** Nächster Modus im Zyklus (für die Umschalt-Taste). */
    public Gamemode next() {
        Gamemode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
