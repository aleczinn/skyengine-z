package de.skyengine.audio;

import de.skyengine.game.world.item.ToolType;
import de.skyengine.utils.logging.LogManager;

/**
 * Sound-Gruppe eines Blocks (wie Minecrafts SoundType): bestimmt die Datei-Basisnamen unter
 * {@code game/sounds/step/} (Laufen, Hit) und {@code game/sounds/dig/} (Abbau, Platzieren).
 * Zuordnung: explizites {@code "sound"}-Feld in der Block-JSON oder Ableitung aus Tool/Archetyp
 * ({@link #resolve}).
 */
public enum BlockSoundGroup {

    STONE("stone", "stone"),
    WOOD("wood", "wood"),
    GRAVEL("gravel", "gravel"),
    GRASS("grass", "grass"),
    SAND("sand", "sand"),
    SNOW("snow", "snow"),
    CLOTH("cloth", "cloth"),
    /** Wie MC: Schritte auf Glas klingen nach Stein, der Bruch nach Glas (random/glass -> dig/glass),
     *  Platzieren wieder nach Stein (Glas klirrt nur beim Zerbrechen). */
    GLASS("stone", "glass", "stone"),
    /** Slimeblock: Bruch/Platzieren aus den grossen, Schritte/Schlaege aus den kleinen
     *  Schleim-Sounds (MC-Events block.slime_block.*). */
    SLIME("slime", "slime"),
    /** Honigblock (MC-Events block.honey_block.*). */
    HONEY("honey", "honey");

    /** Datei-Basisname unter game/sounds/step/ (Varianten 1..N). */
    public final String stepName;
    /** Datei-Basisname unter game/sounds/dig/ (Varianten 1..N). */
    public final String digName;
    /** Datei-Basisname fürs PLATZIEREN — zeigt auf eine dig-Basis (es gibt keine place-Assets). */
    public final String placeName;

    BlockSoundGroup(String stepName, String digName) {
        this(stepName, digName, digName);
    }

    BlockSoundGroup(String stepName, String digName, String placeName) {
        this.stepName = stepName;
        this.digName = digName;
        this.placeName = placeName;
    }

    /**
     * Löst die Sound-Gruppe eines Blocks auf: explizites JSON-Feld gewinnt (unbekannter Wert →
     * Warnung + STONE); sonst Ableitung aus Tool (PICKAXE→Stein, AXE→Holz, SHOVEL→Kies/Erde)
     * bzw. Archetyp (cross/tall_cross = Pflanzen → Gras); Fallback STONE.
     */
    public static BlockSoundGroup resolve(String soundJson, ToolType tool, String archetype) {
        if (soundJson != null) {
            try {
                return valueOf(soundJson.toUpperCase());
            } catch (IllegalArgumentException e) {
                LogManager.getLogger(BlockSoundGroup.class.getName())
                        .warning("Unbekannte Sound-Gruppe \"" + soundJson + "\" — Fallback STONE.");
                return STONE;
            }
        }
        if (tool == ToolType.AXE) return WOOD;
        if (tool == ToolType.SHOVEL) return GRAVEL;
        if ("cross".equals(archetype) || "tall_cross".equals(archetype)) return GRASS;
        return STONE;
    }
}
