package de.skyengine.game.world.tick;

import java.util.Comparator;

/**
 * Persistierter Tick mit stabiler Zielidentitaet und Reihenfolge. {@code expectedBlock}
 * enthaelt beim Typ {@code block} die stabile Block-ID; null kennzeichnet alte v2-Daten,
 * die beim Wiederherstellen einmalig an den aktuell vorhandenen Block gebunden werden.
 */
public record SavedTick(String type, String expectedBlock, int x, int y, int z,
                        int remainingTicks, int priority, long subOrder) {

    public static final Comparator<SavedTick> ORDER = Comparator
            .comparingInt(SavedTick::remainingTicks)
            .thenComparingInt(SavedTick::priority)
            .thenComparingLong(SavedTick::subOrder)
            .thenComparingInt(SavedTick::x)
            .thenComparingInt(SavedTick::y)
            .thenComparingInt(SavedTick::z)
            .thenComparing(SavedTick::type)
            .thenComparing(SavedTick::expectedBlock,
                    Comparator.nullsFirst(Comparator.naturalOrder()));

    /** Quellkompatibler Konstruktor fuer Werkzeuge; neue Runtime-Snapshots liefern alle Felder. */
    public SavedTick(String type, int x, int y, int z, int remainingTicks) {
        this(type, null, x, y, z, remainingTicks, 0, 0);
    }
}
