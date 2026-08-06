package de.skyengine.game.world.tick;

/**
 * Reihenfolge geplanter Block-Ticks mit denselben Zahlenwerten wie Minecraft Java.
 * Kleinere Werte werden innerhalb desselben Game-Ticks zuerst ausgefuehrt.
 */
public enum TickPriority {

    EXTREMELY_HIGH(-3),
    VERY_HIGH(-2),
    HIGH(-1),
    NORMAL(0),
    LOW(1),
    VERY_LOW(2),
    EXTREMELY_LOW(3);

    private final int value;

    TickPriority(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }
}
