package de.skyengine.graphics.gui;

/**
 * Bereich, zu dem ein {@link Slot} gehört. Erst dadurch ist die Frage „wohin wandert ein
 * Shift-Klick?" beantwortbar: Quickmove läuft über Slot-Bereiche, nicht über
 * {@code ItemStorage} — die Doppeltruhe verwendet dafür ein gemeinsames Compound-Storage, ist für
 * den Spieler aber EIN Ziel.
 */
public enum SlotGroup {

    /** Der Block-Container des Screens (Truhe, später Ofen o.ä.). */
    CONTAINER,
    /** Eingaberaster eines Crafting-Menues. */
    CRAFT_INPUT,
    /** Virtueller Ergebnis-Slot eines Crafting-Menues. */
    CRAFT_RESULT,
    /** Materialeingabe einer Maschine. */
    MACHINE_INPUT,
    /** Brennstoffslot einer Maschine. */
    MACHINE_FUEL,
    /** Nicht befuellbare Maschinenausgabe. */
    MACHINE_OUTPUT,
    /** Spieler-Hauptinventar (Storage-Indizes 9..35). */
    INVENTORY,
    /** Spieler-Hotbar (Storage-Indizes 0..8). */
    HOTBAR
}
