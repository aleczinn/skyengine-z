package de.skyengine.game.world.generator.climate;

import de.skyengine.game.world.generator.WorldgenSeeds;
import de.skyengine.utils.math.FastNoiseLite;

/**
 * Pures Klima-Sampling: vier unabhaengige, niederfrequente Noise-Layer (Regionsgroesse einige
 * tausend Bloecke). Threadsicher, da alle FastNoiseLite-Instanzen nur im Konstruktor
 * konfiguriert und danach ausschliesslich gelesen werden (Muster wie MountainWorldGeneratorV1).
 *
 * <p>Seed-Offsets werden zentral in {@link WorldgenSeeds} vergeben (dieser Sampler: 0..8
 * plus WARP_X/WARP_Z fuer den Klassifikations-Warp) — neue Noises dort eintragen, damit
 * keine Layer korrelieren.
 */
public final class ClimateSampler {

    /* Domain-Warp der Klassifikations-Koordinaten: Amplitude in Bloecken und Frequenz der
     * Verschiebefelder. Ersetzt das fruehere additive Grenz-Dither — das verrauschte die
     * Felder PRO BLOCK (bei |Gradient| ~0.001/Block effektiv ±50 Bloecke Streuung -> Speckle),
     * der Warp verschiebt die GrenzLINIE dagegen kohaerent (zusammenhaengend wellig). */
    private static final float WARP_AMP = 24F;
    private static final float WARP_FREQ = 0.006F;

    private final FastNoiseLite temperatureNoise;
    private final FastNoiseLite humidityNoise;
    private final FastNoiseLite continentalnessNoise;
    private final FastNoiseLite erosionNoise;
    private final FastNoiseLite warpXNoise;
    private final FastNoiseLite warpZNoise;
    /* Fraktales Kuestendetail: verschiebt die Kuestenlinie kleinraeumig um ±30-60 Bloecke —
     * bei den grossraeumigen Kontinent-Frequenzen waere die Kueste sonst eine glatte Kurve */
    private final FastNoiseLite coastDetailNoise;

    public ClimateSampler(int seed) {
        /* Klimazonen: sehr grossraeumig (Regionen mehrere tausend Bloecke), wenige Oktaven */
        this.temperatureNoise = fbm(seed + WorldgenSeeds.TEMPERATURE, 2, 0.0002F);
        this.humidityNoise = fbm(seed + WorldgenSeeds.HUMIDITY, 2, 0.00028F);
        /* Kontinente/Ozeane: am grossraeumigsten, sonst zerfallen Ozeane in kleine Flecken */
        this.continentalnessNoise = fbm(seed + WorldgenSeeds.CONTINENTALNESS, 3, 0.00015F);
        this.erosionNoise = fbm(seed + WorldgenSeeds.EROSION, 3, 0.0005F);

        /* Zwei unabhaengige Verschiebefelder fuer den Domain-Warp der Klassifikation */
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
     * Klima am DOMAIN-GEWARPTEN Punkt — fuer die Biom-KLASSIFIKATION (Lookup, Material,
     * Vegetation, Tints): die Sample-Koordinaten werden kohaerent um bis zu ±{@link #WARP_AMP}
     * Bloecke verschoben, Biomgrenzen werden dadurch zusammenhaengende wellige Linien.
     * Ist eine volle zweite Feld-Auswertung (andere Position!) — das Hoehenmodell nutzt
     * weiterhin {@link #sampleSmooth} an den echten Koordinaten.
     */
    public Climate sample(int x, int z) {
        int wx = x + (int) (this.warpXNoise.GetNoise(x, z) * WARP_AMP);
        int wz = z + (int) (this.warpZNoise.GetNoise(x, z) * WARP_AMP);
        return this.sampleSmooth(wx, wz);
    }

    /** Klima OHNE Warp — fuer Hoehenmodell (glatte Verlaeufe, exakte Position). */
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
