package de.skyengine.game.world.generator.climate;

import de.skyengine.utils.math.FastNoiseLite;

/**
 * Pures Klima-Sampling: vier unabhaengige, niederfrequente Noise-Layer (Regionsgroesse einige
 * tausend Bloecke). Threadsicher, da alle FastNoiseLite-Instanzen nur im Konstruktor
 * konfiguriert und danach ausschliesslich gelesen werden (Muster wie MountainWorldGeneratorV1).
 *
 * <p>Reservierte Seed-Offsets dieses Samplers: seed+0 .. seed+6 (inkl. Fluss-Noise spaeter).
 * Generator-eigene Noises muessen ab seed+10 starten, damit keine Layer korrelieren.
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
    /* Fluesse: Domain-Warp verbiegt die Nulllinien des River-Noise zu Maeandern */
    private final FastNoiseLite riverWarp;
    private final FastNoiseLite riverNoise;

    public ClimateSampler(int seed) {
        /* Klimazonen: sehr grossraeumig, wenige Oktaven reichen */
        this.temperatureNoise = fbm(seed, 2, 0.0005F);
        this.humidityNoise = fbm(seed + 1, 2, 0.0007F);
        /* Kontinente/Ozeane: am grossraeumigsten, sonst zerfallen Ozeane in kleine Flecken */
        this.continentalnessNoise = fbm(seed + 2, 3, 0.0004F);
        this.erosionNoise = fbm(seed + 3, 3, 0.0011F);

        /* Hochfrequentes Dither fuer wackelige statt gerade Grenzlinien */
        this.ditherNoise = fbm(seed + 4, 1, 0.05F);

        this.riverWarp = new FastNoiseLite(seed + 5);
        this.riverWarp.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        this.riverWarp.SetDomainWarpAmp(80F);
        this.riverWarp.SetFrequency(0.003F);

        /* Lange, niederfrequente Strukturen — die Naehe zur Nulllinie wird zum Flusslauf */
        this.riverNoise = fbm(seed + 6, 2, 0.0009F);
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
                this.continentalnessNoise.GetNoise(x, z) + dither,
                this.erosionNoise.GetNoise(x, z) + dither);
    }

    /** |domain-gewarpter River-Noise|: 0 = Flussmitte, waechst zum Ufer hin. */
    public float riverValue(int x, int z) {
        FastNoiseLite.Vector2 pos = new FastNoiseLite.Vector2(x, z);
        this.riverWarp.DomainWarp(pos);
        return Math.abs(this.riverNoise.GetNoise(pos.x, pos.y));
    }

    /** Klima OHNE Dither — fuer Hoehenmodell und Tint-Grid (glatte Verlaeufe, kein Rauschen). */
    public Climate sampleSmooth(int x, int z) {
        return new Climate(
                this.temperatureNoise.GetNoise(x, z),
                this.humidityNoise.GetNoise(x, z),
                this.continentalnessNoise.GetNoise(x, z),
                this.erosionNoise.GetNoise(x, z));
    }
}
