package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;

/**
 * Auf-/Zu-Sound eines Blocks (Tür, Truhe) — bewusst ein eigenes Konzept neben
 * {@link BlockSoundGroup}, genau wie Minecrafts {@code BlockSetType} neben dem {@code SoundType}:
 * Eichenbretter und Eichentür klingen beim Abbauen gleich (beide WOOD), aber nur die Tür geht auf.
 *
 * <p>Die Dateien liegen unter {@code game/sounds/<folder>/open*.ogg} bzw. {@code close*.ogg}
 * (nummerierte Varianten oder eine Einzeldatei, siehe {@code SoundManager.loadVariants}). Jeder
 * Satz braucht einen EIGENEN Ordner — die Extraktion behält die Minecraft-Dateinamen, und
 * {@code wooden_door/open1.ogg} und {@code iron_door/open1.ogg} würden sich sonst überschreiben.
 *
 * <p>Eine neue Türart ist damit: Assets ablegen, eine Konstante hier, eine Zeile
 * {@code "open_sound"} in der Block-JSON.
 */
public enum BlockOpenSound {

    WOOD_DOOR("door/wood", 1.0F, false),
    /** Registriert und vorgeladen; einen Eisentür-Block gibt es noch nicht. */
    IRON_DOOR("door/iron", 1.0F, false),
    CHEST("chest", 0.5F, true);

    /** Unterordner unter {@code game/sounds/} mit den open-/close-Varianten. */
    public final String folder;
    /** Lautstärke wie in Vanilla (Tür 1.0, Truhe 0.5). */
    public final float gain;
    /** Zufalls-Pitch: Vanilla variiert die Truhe, lässt die Tür aber fest klingen. */
    public final boolean pitchJitter;

    BlockOpenSound(String folder, float gain, boolean pitchJitter) {
        this.folder = folder;
        this.gain = gain;
        this.pitchJitter = pitchJitter;
    }

    /**
     * Löst den Auf/Zu-Sound eines Blocks auf: explizites JSON-Feld {@code open_sound} gewinnt
     * (unbekannter Wert → Warnung + {@code null}); sonst Ableitung aus dem Archetyp (door →
     * WOOD_DOOR, damit eine neue Holztür nicht stumm bleibt, wenn das Feld vergessen wird).
     *
     * @return {@code null}, wenn der Block keinen Auf/Zu-Sound hat — das ist der Normalfall.
     */
    public static BlockOpenSound resolve(String json, String archetype) {
        if (json != null) {
            try {
                return valueOf(json.toUpperCase());
            } catch (IllegalArgumentException e) {
                LogManager.getLogger(BlockOpenSound.class.getName())
                        .warning("Unbekannter Oeffnungs-Sound \"" + json + "\" — Block bleibt stumm.");
                return null;
            }
        }
        return "door".equals(archetype) ? WOOD_DOOR : null;
    }
}
