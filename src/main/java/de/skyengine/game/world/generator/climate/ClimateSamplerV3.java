package de.skyengine.game.world.generator.climate;

import de.skyengine.game.world.generator.WorldgenSeeds;
import de.skyengine.utils.math.FastNoiseLite;

/**
 * Klima-Sampling fuer den V3-Generator: die vier Felder aus {@link ClimateSampler} (identische
 * Frequenzen und Seed-Offsets 0..8 — gleiche Semantik, bewusst gleiche Buchfuehrung) plus das
 * fuenfte Feld {@code variant} ({@link WorldgenSeeds#VARIANT}). Threadsicher nach demselben
 * Muster: alle FastNoiseLite-Instanzen werden nur im Konstruktor konfiguriert.
 *
 * <p>Eigene Klasse statt Erweiterung von {@link ClimateSampler}: das fuenfte Feld steckt in
 * einem eigenen Record ({@link ClimateV3}), und die beiden Generatoren sollen unabhaengig
 * voneinander aenderbar bleiben.
 */
public final class ClimateSamplerV3 {

    /* Domain-Warp der Klassifikations-Koordinaten (identische Parameter wie ClimateSampler,
     * s. dort fuer die Herleitung Warp statt additivem Dither) */
    private static final float WARP_AMP = 24F;
    private static final float WARP_FREQ = 0.006F;

    private final FastNoiseLite temperatureNoise;
    private final FastNoiseLite humidityNoise;
    private final FastNoiseLite continentalnessNoise;
    private final FastNoiseLite erosionNoise;
    private final FastNoiseLite variantNoise;
    private final FastNoiseLite warpXNoise;
    private final FastNoiseLite warpZNoise;
    /* Fraktales Kuestendetail: verschiebt die Kuestenlinie kleinraeumig (s. ClimateSampler) */
    private final FastNoiseLite coastDetailNoise;

    public ClimateSamplerV3(int seed) {
        this.temperatureNoise = fbm(seed + WorldgenSeeds.TEMPERATURE, 2, 0.0002F);
        this.humidityNoise = fbm(seed + WorldgenSeeds.HUMIDITY, 2, 0.00028F);
        this.continentalnessNoise = fbm(seed + WorldgenSeeds.CONTINENTALNESS, 3, 0.00015F);
        this.erosionNoise = fbm(seed + WorldgenSeeds.EROSION, 3, 0.0005F);
        /* Variante: groesser als Erosion, kleiner als Temperatur — Biom-Flecken ~2-3k Bloecke */
        this.variantNoise = fbm(seed + WorldgenSeeds.VARIANT, 2, 0.0004F);

        this.warpXNoise = fbm(seed + WorldgenSeeds.WARP_X, 2, WARP_FREQ);
        this.warpZNoise = fbm(seed + WorldgenSeeds.WARP_Z, 2, WARP_FREQ);
        this.coastDetailNoise = fbm(seed + WorldgenSeeds.COAST_DETAIL, 3, 0.004F);
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
     * Klima am DOMAIN-GEWARPTEN Punkt — fuer die Biom-KLASSIFIKATION (argmax in BiomeWeights,
     * Material, Vegetation, Tints): kohaerent verschobene Grenzlinien statt Speckle
     * (s. {@link ClimateSampler#sample}). Terrain-Gewichte ({@code BiomeWeights.blend})
     * nutzen weiterhin {@link #sampleSmooth} an den echten Koordinaten.
     */
    public ClimateV3 sample(int x, int z) {
        int wx = x + (int) (this.warpXNoise.GetNoise(x, z) * WARP_AMP);
        int wz = z + (int) (this.warpZNoise.GetNoise(x, z) * WARP_AMP);
        return this.sampleSmooth(wx, wz);
    }

    /** Klima OHNE Warp — fuer Hoehenmodell und Terrain-Gewichte (exakte Position). */
    public ClimateV3 sampleSmooth(int x, int z) {
        return new ClimateV3(
                this.temperatureNoise.GetNoise(x, z),
                this.humidityNoise.GetNoise(x, z),
                this.continentalness(x, z),
                this.erosionNoise.GetNoise(x, z),
                this.variantNoise.GetNoise(x, z));
    }

    /* Kontinentalitaet inkl. Kuestendetail — in beiden Sample-Pfaden identisch (s. ClimateSampler) */
    private float continentalness(int x, int z) {
        return this.continentalnessNoise.GetNoise(x, z)
                + this.coastDetailNoise.GetNoise(x, z) * 0.02F;
    }
}
