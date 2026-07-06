package de.skyengine.game.world.generator.climate;

import de.skyengine.utils.math.FastNoiseLite;

/**
 * Pures Klima-Sampling: vier unabhaengige, niederfrequente Noise-Layer (Regionsgroesse einige
 * tausend Bloecke). Threadsicher, da alle FastNoiseLite-Instanzen nur im Konstruktor
 * konfiguriert und danach ausschliesslich gelesen werden (Muster wie MountainWorldGeneratorV1).
 *
 * <p>Reservierte Seed-Offsets dieses Samplers: seed+0 .. seed+8 (seed+5/+6/+8 sind seit dem
 * Wechsel aufs RiverNetwork frei, bleiben aber reserviert). Generator-eigene Noises muessen
 * ab seed+10 starten, damit keine Layer korrelieren.
 */
public final class ClimateSampler {

    /* Staerke des Grenz-Dithers: verwackelt Biomgrenzen in einem ~10-20-Block-Streifen
     * (verallgemeinertes LINE_DITHER-Prinzip aus MountainWorldGeneratorV1) */
    private static final float DITHER_STRENGTH = 0.05F;

    private final FastNoiseLite temperatureNoise;
    private final FastNoiseLite humidityNoise;
    private final FastNoiseLite continentalnessNoise;
    private final FastNoiseLite erosionNoise;
    private final FastNoiseLite ditherNoise;
    /* Fraktales Kuestendetail: verschiebt die Kuestenlinie kleinraeumig um ±30-60 Bloecke —
     * bei den grossraeumigen Kontinent-Frequenzen waere die Kueste sonst eine glatte Kurve */
    private final FastNoiseLite coastDetailNoise;

    public ClimateSampler(int seed) {
        /* Klimazonen: sehr grossraeumig (Regionen mehrere tausend Bloecke), wenige Oktaven */
        this.temperatureNoise = fbm(seed, 2, 0.0002F);
        this.humidityNoise = fbm(seed + 1, 2, 0.00028F);
        /* Kontinente/Ozeane: am grossraeumigsten, sonst zerfallen Ozeane in kleine Flecken */
        this.continentalnessNoise = fbm(seed + 2, 3, 0.00015F);
        this.erosionNoise = fbm(seed + 3, 3, 0.0005F);

        /* Hochfrequentes Dither fuer wackelige statt gerade Grenzlinien */
        this.ditherNoise = fbm(seed + 4, 1, 0.05F);

        this.coastDetailNoise = fbm(seed + 7, 3, 0.004F);
    }

    private static FastNoiseLite fbm(int seed, int octaves, float frequency) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(octaves);
        noise.SetFrequency(frequency);
        return noise;
    }

    /**
     * Klima MIT Grenz-Dither — fuer Biome-Lookup und Oberflaechenmaterial. An Schwellwerten
     * flackert das Ergebnis dadurch in einem schmalen Streifen zwischen beiden Seiten:
     * probabilistische Materialmischung an Biomgrenzen ohne zusaetzlichen Blending-Code.
     */
    public Climate sample(int x, int z) {
        float dither = this.ditherNoise.GetNoise(x, z) * DITHER_STRENGTH;
        return new Climate(
                this.temperatureNoise.GetNoise(x, z) + dither,
                this.humidityNoise.GetNoise(x, z) + dither,
                this.continentalness(x, z) + dither,
                this.erosionNoise.GetNoise(x, z) + dither);
    }

    /** Klima OHNE Dither — fuer Hoehenmodell und Tint-Grid (glatte Verlaeufe, kein Rauschen). */
    public Climate sampleSmooth(int x, int z) {
        return new Climate(
                this.temperatureNoise.GetNoise(x, z),
                this.humidityNoise.GetNoise(x, z),
                this.continentalness(x, z),
                this.erosionNoise.GetNoise(x, z));
    }

    /* Kontinentalitaet inkl. Kuestendetail — in BEIDEN Sample-Pfaden identisch, damit
     * Biome, Hoehenmodell und Material dieselbe (fraktale) Kuestenlinie sehen. */
    private float continentalness(int x, int z) {
        return this.continentalnessNoise.GetNoise(x, z)
                + this.coastDetailNoise.GetNoise(x, z) * 0.02F;
    }
}
