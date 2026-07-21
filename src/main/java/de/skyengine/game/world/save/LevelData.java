package de.skyengine.game.world.save;

import java.util.ArrayList;
import java.util.List;

/**
 * Welt-Metadaten für {@code saves/<ordner>/level.json} (GSON-DTO). Chunks werden NICHT
 * gespeichert — die Welt regeneriert aus dem Seed; Block-Änderungen gehen beim Verlassen
 * verloren (bewusster Zwischenstand, volle Chunk-Persistenz ist ein eigenes Projekt).
 */
public final class LevelData {

    public String name;
    public int seed;
    public long created;
    public long lastPlayed;

    /** null, bis die Welt zum ersten Mal verlassen/gespeichert wurde. */
    public PlayerData player;
    /** Nur belegte Slots. */
    public List<ItemEntry> inventory = new ArrayList<>();

    public static final class PlayerData {
        public double x, y, z;
        public float yaw, pitch;
        /** Enum-Name (robust gegen Ordinal-Änderungen). */
        public String gamemode;
        public boolean flying;
        /* Vitals bewusst Boxed: alte level.json ohne die Felder -> null -> volle Werte beim
           Laden (ein primitives 0.0 hieße "tot beim Betreten"). */
        public Float health;
        public Integer foodLevel;
        public Float saturation;
    }

    public static final class ItemEntry {
        public int slot;
        public String id;
        public int count;
        public int damage;
    }
}
