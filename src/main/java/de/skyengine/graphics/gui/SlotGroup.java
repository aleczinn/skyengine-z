package de.skyengine.graphics.gui;

/**
 * Bereich, zu dem ein {@link Slot} gehört. Erst dadurch ist die Frage „wohin wandert ein
 * Shift-Klick?" beantwortbar: Quickmove läuft über Slot-Bereiche, nicht über
 * {@code ItemStorage} — die Doppeltruhe verteilt ihre beiden Hälften auf zwei Storages, ist für
 * den Spieler aber EIN Ziel.
 */
public enum SlotGroup {

    /** Der Block-Container des Screens (Truhe, später Ofen o.ä.). */
    CONTAINER,
    /** Spieler-Hauptinventar (Storage-Indizes 9..35). */
    INVENTORY,
    /** Spieler-Hotbar (Storage-Indizes 0..8). */
    HOTBAR
}
