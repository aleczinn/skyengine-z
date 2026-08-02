package de.skyengine.game.world.block.state;

/**
 * Wie sich Redstone-Staub zu einer horizontalen Seite hin verbindet: gar nicht, flach
 * auf dem Boden oder an der Blockwand hochgezogen (Vanilla: none/side/up).
 * NONE steht bewusst zuerst — der erste Enum-Wert ist der Property-Default.
 */
public enum RedstoneSide {
    NONE,
    SIDE,
    UP;

    /** Verbunden (egal ob flach oder hochgezogen)? */
    public boolean isConnected() {
        return this != NONE;
    }
}
