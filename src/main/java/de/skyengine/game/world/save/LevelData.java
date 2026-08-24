package de.skyengine.game.world.save;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Welt-Metadaten für {@code saves/<ordner>/level.json} (GSON-DTO). Chunks liegen in
 * {@code region/*.srg} (nur modifizierte; s. WorldStorage), der Spieler in
 * {@code player/player.dat} — level.json hält NUR noch Welt-Metadaten.
 * Alle neuen Felder sind Boxed/null-tolerant (alte level.json laden mit Defaults).
 */
public final class LevelData {

    public String name;
    public int seed;
    public long created;
    public long lastPlayed;

    /** Version des Save-Layouts (null = 1). Strikt getrennt von der Chunk-payloadVersion. */
    public Integer formatVersion;
    /** "default" (generiert) oder "imported" (MC-Import, Void-Generator). null = default. */
    public String worldType;
    /** Generator-Kennung (Provenienz), z.B. "alpha_v2" / "minecraft_import". null = alpha_v2. */
    public String generator;
    /** Version des Generators, mit dem die Welt läuft (Mismatch -> Warnung). null = 1. */
    public Integer generatorVersion;

    /** Dimensions-Metadaten; fehlt bei Format 1 und wird dann aus den Root-Feldern migriert. */
    public Map<String, DimensionData> dimensions = new LinkedHashMap<>();

    /** NUR noch Migration: Alt-Saves vor player.dat. Wird beim nächsten Speichern genullt. */
    public PlayerData player;
    /** NUR noch Migration, s. {@link #player}. */
    public List<ItemEntry> inventory = new ArrayList<>();

    /** Zustände der benannten Loot-Zufallsfolgen; leer/null bei älteren Welten. */
    public Map<String, Long> lootRandomStates = new LinkedHashMap<>();
    /** Vanilla-Gamerule; false/null ist der Standard der aktuellen Java Edition. */
    public Boolean tntExplosionDropDecay;

    public static final class DimensionData {
        public int seed;
        public String generator;
        public Integer generatorVersion;
    }

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
