package de.skyengine.game.world.generator.biome;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.generator.biome.Biome.PlantEntry;
import de.skyengine.game.world.generator.biome.Biome.TreeEntry;
import de.skyengine.game.world.generator.climate.Climate;
import de.skyengine.game.world.generator.feature.trees.TreeShapes;

/**
 * Statische Biome-Registry + Klima-Lookup. Aus den vier Klimawerten wird das Biom rein ueber
 * Schwellwerte abgeleitet — unpassende Nachbarschaften (Wueste neben Jungle) sind damit
 * konstruktionsbedingt ausgeschlossen, weil die Klimafelder stetig sind.
 *
 * <p><b>Achtung Init-Reihenfolge:</b> Die Konstanten fangen {@code Blocks.*}-IDs beim
 * Klassen-Init ein. Diese Klasse darf deshalb erst NACH {@code Blocks.bootstrap(...)}
 * erstmals beruehrt werden — also nur aus Laufzeitpfaden (generate/biomeAt/Features),
 * NIE aus einem Generator-Konstruktor (World wird vor dem Block-Bootstrap erzeugt!).
 */
public final class Biomes {

    /* Kontinentalitaets-Schwellen: darunter Ozean bzw. Strandband. Das Band ist bewusst
     * schmal (0.06): der Kontinentalitaets-Gradient ist seit der Frequenz-Absenkung flacher,
     * dieselbe Schwellen-Spanne wird dadurch in Bloecken deutlich breiter. */
    public static final float C_OCEAN = -0.19F;
    public static final float C_BEACH = -0.13F;

    /* Ab diesem mountainWeight gilt eine Position als Extreme Hills */
    public static final float MOUNTAIN_THRESHOLD = 0.5F;

    public static final Biome OCEAN = new Biome(0, "ocean",
            Blocks.GRAVEL, Blocks.GRAVEL, 0x8EB971, 0x71A74D, 0x1F3C99,
            0F, Biome.NO_TREES, 0F, Biome.NO_PLANTS);
    public static final Biome BEACH = new Biome(1, "beach",
            Blocks.SAND, Blocks.SANDSTONE, 0x91BD59, 0x77AB2F, 0xF7E9A3,
            0F, Biome.NO_TREES, 0F, Biome.NO_PLANTS);
    public static final Biome CARIBBEAN_BEACH = new Biome(2, "caribbean_beach",
            Blocks.SAND, Blocks.SANDSTONE, 0x64C93F, 0x30BB0B, 0x2FD5C8,
            0.25F, new TreeEntry[]{new TreeEntry(1, TreeShapes.PALM)},
            0F, Biome.NO_PLANTS);
    public static final Biome PLAINS = new Biome(3, "plains",
            Blocks.GRASS_BLOCK, Blocks.DIRT, 0x91BD59, 0x77AB2F, 0x8DB360,
            0.04F, new TreeEntry[]{new TreeEntry(4, TreeShapes.OAK), new TreeEntry(1, TreeShapes.BIRCH)},
            0.40F, new PlantEntry[]{new PlantEntry(70, Blocks.SHORT_GRASS), new PlantEntry(12, Blocks.TALL_GRASS),
                    new PlantEntry(5, Blocks.POPPY), new PlantEntry(5, Blocks.DANDELION),
                    new PlantEntry(3, Blocks.ORANGE_TULIP)});
    public static final Biome DESERT = new Biome(4, "desert",
            Blocks.SAND, Blocks.SANDSTONE, 0xBFB755, 0xAEA42A, 0xFA9418,
            0F, Biome.NO_TREES,
            0.015F, new PlantEntry[]{new PlantEntry(1, Blocks.DEAD_BUSH)});
    public static final Biome JUNGLE = new Biome(5, "jungle",
            Blocks.GRASS_BLOCK, Blocks.DIRT, 0x59C93C, 0x30BB0B, 0x537B09,
            0.65F, new TreeEntry[]{new TreeEntry(4, TreeShapes.JUNGLE), new TreeEntry(1, TreeShapes.OAK)},
            0.55F, new PlantEntry[]{new PlantEntry(45, Blocks.SHORT_GRASS), new PlantEntry(30, Blocks.FERN),
                    new PlantEntry(25, Blocks.TALL_GRASS)});
    public static final Biome SPRUCE_FOREST = new Biome(6, "spruce_forest",
            Blocks.GRASS_BLOCK, Blocks.DIRT, 0x86B783, 0x68A464, 0x0B6659,
            0.45F, new TreeEntry[]{new TreeEntry(1, TreeShapes.SPRUCE)},
            0.28F, new PlantEntry[]{new PlantEntry(60, Blocks.SHORT_GRASS), new PlantEntry(40, Blocks.FERN)});
    public static final Biome REDWOOD_FOREST = new Biome(7, "redwood_forest",
            Blocks.GRASS_BLOCK, Blocks.DIRT, 0x86B87F, 0x68A55F, 0x8B4513,
            0.22F, new TreeEntry[]{new TreeEntry(3, TreeShapes.REDWOOD), new TreeEntry(1, TreeShapes.SPRUCE)},
            0.30F, new PlantEntry[]{new PlantEntry(55, Blocks.FERN), new PlantEntry(45, Blocks.SHORT_GRASS)});
    public static final Biome EXTREME_HILLS = new Biome(8, "extreme_hills",
            Blocks.GRASS_BLOCK, Blocks.DIRT, 0x8AB689, 0x6DA36B, 0x606060,
            0.08F, new TreeEntry[]{new TreeEntry(2, TreeShapes.SPRUCE), new TreeEntry(1, TreeShapes.OAK)},
            0.20F, new PlantEntry[]{new PlantEntry(1, Blocks.SHORT_GRASS)});

    public static final Biome[] ALL = {OCEAN, BEACH, CARIBBEAN_BEACH, PLAINS, DESERT,
            JUNGLE, SPRUCE_FOREST, REDWOOD_FOREST, EXTREME_HILLS};

    /*
     * Inland-Zuordnung [Temperatur-Bucket][Feuchtigkeits-Bucket]:
     *                 trocken           mittel            feucht
     *   kalt          Fichtenwald       Fichtenwald       Redwood
     *   gemaessigt    Ebene             Ebene             Redwood
     *   heiss         Wueste            Ebene             Jungle
     */
    private static final Biome[][] INLAND = {
            {SPRUCE_FOREST, SPRUCE_FOREST, REDWOOD_FOREST},
            {PLAINS, PLAINS, REDWOOD_FOREST},
            {DESERT, PLAINS, JUNGLE}};

    public static Biome lookup(Climate c) {
        if (c.continentalness() < C_OCEAN) return OCEAN;
        if (c.continentalness() < C_BEACH) {
            /* Karibikstrand nur in heiss-feuchten Kuestenregionen */
            return (c.temperature() > 0.35F && c.humidity() > 0.1F) ? CARIBBEAN_BEACH : BEACH;
        }
        if (mountainWeight(c) > MOUNTAIN_THRESHOLD) return EXTREME_HILLS;
        return INLAND[tempBucket(c.temperature())][humidityBucket(c.humidity())];
    }

    /**
     * Gebirgs-Gewicht 0..1 aus hoher Kontinentalitaet x niedriger Erosion. WICHTIG: dieselbe
     * Funktion skaliert im Hoehenmodell den Ridged-Berg-Aufschlag — Extreme-Hills-Biom und
     * Berg-Terrain sind dadurch per Konstruktion deckungsgleich.
     */
    public static float mountainWeight(Climate c) {
        float inland = smoothstep((c.continentalness() - 0.02F) / 0.30F);
        float rough = smoothstep((-0.15F - c.erosion()) / 0.35F);
        return inland * rough;
    }

    private static int tempBucket(float temperature) {
        if (temperature < -0.25F) return 0;
        return (temperature < 0.25F) ? 1 : 2;
    }

    private static int humidityBucket(float humidity) {
        if (humidity < -0.2F) return 0;
        return (humidity < 0.2F) ? 1 : 2;
    }

    private static float smoothstep(float t) {
        if (t <= 0F) return 0F;
        if (t >= 1F) return 1F;
        return t * t * (3F - 2F * t);
    }

    private Biomes() {
    }
}
