package de.skyengine.game.world.generator;

/**
 * Zentrale Vergabe der Seed-Offsets aller Weltgen-Noises (Nutzung: {@code seed + KONSTANTE}).
 * Jeder Noise-Layer bekommt hier seinen Eintrag — eine Doppelbelegung korreliert die Felder
 * still (sichtbar nur als "komisches" Terrain, nie als Fehler). Neue Noises nehmen den
 * naechsten freien Offset.
 *
 * <p><b>Werte NIE umnummerieren:</b> sie definieren die generierte Welt eines Seeds.
 */
public final class WorldgenSeeds {

    /* --- ClimateSampler (0..8; 5, 6 und 8 sind seit dem RiverNetwork frei, bleiben reserviert) --- */
    public static final int TEMPERATURE = 0;
    public static final int HUMIDITY = 1;
    public static final int CONTINENTALNESS = 2;
    public static final int EROSION = 3;
    public static final int DITHER = 4;
    public static final int COAST_DETAIL = 7;

    /* --- AlphaWorldGeneratorV2 (10..23) --- */
    public static final int DETAIL = 10;
    /** Bewusst gleicher Seed wie {@link #DETAIL}: detailBase ist die 1. Oktave des Detail-FBm. */
    public static final int DETAIL_BASE = DETAIL;
    public static final int MOUNTAIN = 11;
    /** Bewusst gleicher Basis-Seed wie {@link #MOUNTAIN} — andere Frequenz/Nutzung, keine sichtbare Korrelation. */
    public static final int DETAIL_BASE_2 = MOUNTAIN;
    public static final int FLOOR = 12;
    public static final int VEGETATION = 13;
    public static final int SHAPE = 14;
    public static final int CHEESE = 15;
    public static final int SPAGHETTI_1 = 16;
    public static final int SPAGHETTI_2 = 17;
    public static final int STONE_1 = 18;
    public static final int STONE_2 = 19;
    public static final int UPLIFT = 20;
    public static final int PLATEAU = 21;
    public static final int SNOW_WOBBLE = 22;
    public static final int SEDIMENT = 23;

    /* --- RiverNetwork (24..25) --- */
    public static final int RIVER_MEANDER = 24;
    public static final int RIVER_WIDTH = 25;

    private WorldgenSeeds() {}
}
