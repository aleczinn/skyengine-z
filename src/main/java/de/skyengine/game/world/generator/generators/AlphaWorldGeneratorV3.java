package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.WorldgenSeeds;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.BiomeWeights;
import de.skyengine.game.world.generator.biome.BiomeWeights.TerrainParams;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.generator.climate.ClimateSamplerV3;
import de.skyengine.game.world.generator.climate.ClimateV3;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.utils.math.FastNoiseLite;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Biome-Parameter-Blending-Generator (V3): das Klima-Skelett von V2 (Kontinent-Spline,
 * Uplift, Erosions-Detail) bleibt, aber die biom-individuellen Terrain-Anteile kommen aus
 * {@link BiomeWeights}: pro Spalte werden die naechsten Biom-Profile im 5D-Klimaraum gewichtet
 * gemittelt und deren PARAMETER in die eine geteilte Noise-Basis eingesetzt. Abrupte
 * Uebergaenge (Fjord-Klippen, Canyon-Terrassen) sind Intra-Biom-Shaper ueber der stetigen
 * Kontinentalitaet bzw. Hoehe — nie an Biomgrenzen.
 *
 * <p>Struktur, Purity-Regeln, Seen, Fluss-Netz, 3D-Dichte/Hoehlen und Feature-Vertraege sind
 * aus {@link AlphaWorldGeneratorV2} uebernommen (der bleibt als bit-stabiler Regressionsanker
 * unveraendert). Seed-Offsets zentral in {@link WorldgenSeeds} (V3 nutzt zusaetzlich VARIANT).
 */
public class AlphaWorldGeneratorV3 extends WorldGenerator implements RiverTerrain {

    /* Meeresspiegel: bis zu dieser Hoehe wird Wasser aufgefuellt */
    static final int SEA_LEVEL = 64;
    /* Ab dieser Hoehe: Fels statt Biomdecke (relativ zu lineLift, s. surfaceTop) */
    private static final int STONE_LINE = 125;
    /* Ab dieser Hoehe: Schneekappe */
    private static final int SNOW_LINE = 160;
    /* Verwackelung der Hoehenlinien, damit keine geraden Konturen entstehen (wie V2) */
    private static final float LINE_DITHER = 10F;
    /* Hangneigung gekappt; steckt auch im Gate des (teuren) Neigungs-Tests (wie V2) */
    private static final int SLOPE_MAX = 8;
    private static final int SLOPE_STONE_MAX = SLOPE_MAX * 3;

    /* 3D-Dichte-Gitter wie V2 (4 Bloecke horizontal, 8 vertikal) */
    private static final int GRID_XZ = 4;
    private static final int GRID_Y = 8;
    /* Obergrenze der (jetzt biom-abhaengigen) 3D-Verformung — Puffer fuer Solid-/Top-Margins.
     * MUSS >= max. shapeAmpMax aller Biom-Profile sein! */
    private static final float SHAPE_AMP_CEIL = 16F;
    private static final float CHEESE_THRESHOLD = 0.62F;
    private static final float SPAGHETTI_THRESHOLD = 0.06F;
    private static final float STONE_VEIN_THRESHOLD = 0.62F;

    /* Worley-Seen: identische Konstanten wie V2 (s. dort fuer die Herleitung) */
    private static final int LAKE_CELL = 384;
    private static final int LAKE_JITTER = 100;
    private static final int LAKE_RADIUS_MIN = 40;
    private static final int LAKE_RADIUS_MAX = 90;
    private static final float LAKE_CHANCE = 0.35F;
    private static final int LAKE_DEPTH = 5;
    private static final int LAKE_RING_POINTS = 16;
    private static final int LAKE_MAX_RING_SPAN = 12;

    /* Kontinentalwellen wie V2 */
    private static final float UPLIFT_UP = 75F;
    private static final float UPLIFT_DOWN = 45F;
    private static final float UPLIFT_BIAS = 0.25F;

    /* Fjord-Klippe: steile Wand ueber einem schmalen Kontinentalitaets-Band. Start knapp
     * UNTER C_BEACH — das Strandband schrumpft dadurch auf einen schmalen Saum, direkt
     * dahinter steigt die Wand auf cliffHeight. Die Breite in BLOECKEN haengt vom lokalen
     * c-Gradienten ab (~0.0002-0.001/Block): das Band muss deshalb sehr schmal sein, sonst
     * verschmiert die Wand an flachen Kuesten zur Rampe (bei 0.008: ~8-40 Bloecke Anlauf
     * fuer die volle Hoehe). Die Ortssteuerung kommt aus dem Biom-Gewicht (cliffHeight
     * geblendet) — anderswo entstehen KEINE Klippen. */
    private static final float CLIFF_START = Biomes.C_BEACH - 0.012F;
    private static final float CLIFF_WIDTH = 0.008F;

    /* Canyon-Terrassen: Stufenhoehe; der komplette Hub einer Stufe passiert im mittleren
     * TERRACE_EDGE-Anteil (schmales Band = steile Wand, Rest = ebene Trittflaeche) */
    private static final float TERRACE_STEP = 11F;
    private static final float TERRACE_EDGE = 0.15F;
    /* Terracotta-Schichten nur oberhalb dieser Hoehe (darunter normaler Stein/Adern) */
    private static final int STRATA_MIN_Y = 45;

    private final ClimateSamplerV3 climate;
    /* Noise-Basis identisch zu V2 (gleiche Seed-Offsets/Frequenzen) — die Biom-Profile
     * skalieren nur die AMPLITUDEN, nie eigene Noises pro Biom (Performance + Fluss-Traeger) */
    private final FastNoiseLite upliftNoise;
    private final FastNoiseLite plateauNoise;
    private final FastNoiseLite snowWobbleNoise;
    private final FastNoiseLite detailNoise;
    private final FastNoiseLite detailBaseNoise;
    private final FastNoiseLite detailBase2Noise;
    private final FastNoiseLite mountainNoise;
    private final FastNoiseLite floorNoise;
    private final FastNoiseLite sedimentNoise;
    private final FastNoiseLite vegNoise;
    private final FastNoiseLite shapeNoise;
    private final FastNoiseLite cheeseNoise;
    private final FastNoiseLite spaghettiNoise1;
    private final FastNoiseLite spaghettiNoise2;
    private final FastNoiseLite stoneNoise1;
    private final FastNoiseLite stoneNoise2;

    /* See-Zellen-Cache (pure Memoization, s. V2) */
    private final ConcurrentHashMap<Long, Lake> lakeCache = new ConcurrentHashMap<>();
    private static final Lake NO_LAKE = new Lake(0, 0, 0, 0);

    /* Quelle-zu-Muendung-Flussnetz — geteilte Implementierung via RiverTerrain-Hooks */
    private final RiverNetwork riverNetwork;

    private final AtomicLong generateNanos = new AtomicLong();
    private final AtomicInteger generateCount = new AtomicInteger();
    private final Logger logger = LogManager.getLogger(AlphaWorldGeneratorV3.class.getName());

    public AlphaWorldGeneratorV3(int seed) {
        super(seed);
        /* WICHTIG: hier NIE Biomes/BiomeWeights beruehren — World entsteht vor Blocks.bootstrap */
        this.climate = new ClimateSamplerV3(seed);

        this.detailNoise = new FastNoiseLite(seed + WorldgenSeeds.DETAIL);
        this.detailNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.detailNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.detailNoise.SetFractalOctaves(4);
        this.detailNoise.SetFrequency(0.004F);

        this.detailBaseNoise = new FastNoiseLite(seed + WorldgenSeeds.DETAIL_BASE);
        this.detailBaseNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.detailBaseNoise.SetFrequency(0.004F);

        this.detailBase2Noise = new FastNoiseLite(seed + WorldgenSeeds.DETAIL_BASE_2);
        this.detailBase2Noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.detailBase2Noise.SetFrequency(0.008F);

        this.mountainNoise = new FastNoiseLite(seed + WorldgenSeeds.MOUNTAIN);
        this.mountainNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.mountainNoise.SetFractalType(FastNoiseLite.FractalType.Ridged);
        this.mountainNoise.SetFractalOctaves(4);
        this.mountainNoise.SetFrequency(0.003F);

        this.floorNoise = new FastNoiseLite(seed + WorldgenSeeds.FLOOR);
        this.floorNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.floorNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.floorNoise.SetFractalOctaves(2);
        this.floorNoise.SetFrequency(0.03F);

        this.vegNoise = new FastNoiseLite(seed + WorldgenSeeds.VEGETATION);
        this.vegNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.vegNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.vegNoise.SetFractalOctaves(2);
        this.vegNoise.SetFrequency(0.008F);

        this.shapeNoise = new FastNoiseLite(seed + WorldgenSeeds.SHAPE);
        this.shapeNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.shapeNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.shapeNoise.SetFractalOctaves(3);
        this.shapeNoise.SetFrequency(0.008F);

        this.cheeseNoise = new FastNoiseLite(seed + WorldgenSeeds.CHEESE);
        this.cheeseNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.cheeseNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.cheeseNoise.SetFractalOctaves(3);
        this.cheeseNoise.SetFrequency(0.012F);

        this.spaghettiNoise1 = new FastNoiseLite(seed + WorldgenSeeds.SPAGHETTI_1);
        this.spaghettiNoise1.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.spaghettiNoise1.SetFrequency(0.01F);

        this.spaghettiNoise2 = new FastNoiseLite(seed + WorldgenSeeds.SPAGHETTI_2);
        this.spaghettiNoise2.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.spaghettiNoise2.SetFrequency(0.01F);

        this.stoneNoise1 = new FastNoiseLite(seed + WorldgenSeeds.STONE_1);
        this.stoneNoise1.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.stoneNoise1.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.stoneNoise1.SetFractalOctaves(2);
        this.stoneNoise1.SetFrequency(0.03F);

        this.stoneNoise2 = new FastNoiseLite(seed + WorldgenSeeds.STONE_2);
        this.stoneNoise2.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.stoneNoise2.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.stoneNoise2.SetFractalOctaves(2);
        this.stoneNoise2.SetFrequency(0.03F);

        this.upliftNoise = new FastNoiseLite(seed + WorldgenSeeds.UPLIFT);
        this.upliftNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.upliftNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.upliftNoise.SetFractalOctaves(2);
        this.upliftNoise.SetFrequency(0.00008F);

        this.plateauNoise = new FastNoiseLite(seed + WorldgenSeeds.PLATEAU);
        this.plateauNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.plateauNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.plateauNoise.SetFractalOctaves(2);
        this.plateauNoise.SetFrequency(0.0006F);

        this.snowWobbleNoise = new FastNoiseLite(seed + WorldgenSeeds.SNOW_WOBBLE);
        this.snowWobbleNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.snowWobbleNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.snowWobbleNoise.SetFractalOctaves(2);
        this.snowWobbleNoise.SetFrequency(0.0012F);

        this.sedimentNoise = new FastNoiseLite(seed + WorldgenSeeds.SEDIMENT);
        this.sedimentNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.sedimentNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.sedimentNoise.SetFractalOctaves(2);
        this.sedimentNoise.SetFrequency(0.0015F);

        this.riverNetwork = new RiverNetwork(this, seed);
    }

    /** Fluss-Netz — public fuer Sonden/Debug-Karten (wie {@link #waterLevelAt}). */
    public RiverNetwork riverNetwork() {
        return this.riverNetwork;
    }

    @Override
    public int sampleHeight(int x, int z) {
        ClimateV3 c = this.climate.sampleSmooth(x, z);
        return this.columnFor(x, z, c, BiomeWeights.blend(c), true).height;
    }

    /**
     * Ergebnis der Spaltenberechnung: Hoehe, lokaler Wasserspiegel, 3D-Amplitude,
     * lineLift = Summe aller Nicht-Noise-Grundniveau-Anhebungen (Uplift + Klippe +
     * Biom-Grundniveau; verschiebt die Stein-/Schneegrenze mit) und mesaness =
     * Mesa-Formanteil des Bergterms (steuert Sand- vs. Fels-Boden im Canyon).
     */
    private record ColumnSample(int height, int waterLevel, float shapeAmp, float lineLift,
                                float mesaness) {
    }

    /**
     * Rohhoehe plus Mesa-Formanteil 0..1: wie stark der Ridged-Bergterm diese Spalte real
     * traegt (Form x Amplituden-Faktor). Im Canyon trennt das den Sandboden zwischen den
     * Mesas (niedrig) vom Terracotta-Fels der Mesas selbst (hoch).
     */
    private record Raw(float h, float mesaness) {
    }

    /**
     * Berechnet eine Terrainspalte komplett (Struktur wie V2): Rohhoehe aus geblendeten
     * Biom-Parametern, Fluss- und See-Carving, lokaler Wasserspiegel, 3D-Amplitude.
     * {@code withWater=false} (Basis der Seespiegel-Ringpunkte) laesst See- und
     * Fluss-Carving weg — Seen duerfen nicht von Fluessen abhaengen (Cache-Rekursion!).
     */
    private ColumnSample columnFor(int x, int z, ClimateV3 c, TerrainParams p, boolean withWater) {
        float uplift = this.upliftOffset(x, z, c);
        float cliff = cliffLift(c.continentalness(), p.cliffHeight());
        float baseLift = p.baseOffset() * inlandGate(c.continentalness());
        Raw rawSample = this.rawHeight(x, z, c, p, uplift, cliff, baseLift);
        float raw = rawSample.h();
        float h = raw;
        int waterLevel = SEA_LEVEL;
        int riverWater = Integer.MIN_VALUE;
        float damp = 1F;

        /* Fluss: Kanal + Tal um die Polylinie des Netzes formen (identisch zu V2) */
        if (withWater) {
            RiverNetwork.Sample river = this.riverNetwork.sampleAt(x, z);
            if (river != null) {
                float spiegel = river.surf();
                float valleyHalf = river.half() * RiverNetwork.VALLEY_FACTOR;
                float shoulder = valleyHalf * RiverNetwork.SHOULDER_FACTOR;
                /* Uferdamm: einzige Terrain-ANHEBUNG des Fluss-Systems (s. V2) */
                if (raw < spiegel + 1F) {
                    float lift = Math.min(4F, spiegel + 1F - raw);
                    float fade = Math.min(1F, (shoulder - river.dist()) / (shoulder - valleyHalf));
                    h = Math.max(h, raw + lift * smoothstep(fade));
                }
                if (river.dist() < valleyHalf) {
                    float t = smoothstep(1F - river.dist() / valleyHalf);
                    float carve = Math.min(1F, t * Math.clamp(13F / river.half(), 1.1F, 2.4F));
                    float effWater = Math.min(spiegel, raw + 2F);
                    float depthVar = (this.sedimentNoise.GetNoise(x * 1.3F + 557F, z * 1.3F + 557F) + 1F) * 0.5F;
                    float bed = effWater - 2F - depthVar * 2F - Math.min(6F, Math.max(0F, river.half() - 4F) * 0.7F);
                    if (h > bed) h = lerp(h, bed, carve);
                    damp *= 1F - carve;
                    riverWater = (int) effWater;
                }
            }
        }

        /* See: Becken glockenfoermig unter den Spiegel carven (identisch zu V2) */
        if (withWater) {
            Lake lake = this.lakeAt(x, z);
            if (lake != null) {
                float t = Math.min(1F, smoothstep(1F - this.lakeShoreNorm(lake, x, z)) * 1.3F);
                float target = lake.level() - LAKE_DEPTH;
                if (h > target) h = lerp(h, target, t);
                damp *= 1F - t;
                waterLevel = Math.max(waterLevel, lake.level());
                riverWater = Math.min(riverWater, Math.max(lake.level(), (int) h + 2));
            }
        }
        waterLevel = Math.max(waterLevel, riverWater);

        int height = Math.clamp((int) h, 8, Chunk.HEIGHT - 2);
        return new ColumnSample(height, waterLevel,
                p.shapeAmpMax() * this.ruggedness(c) * damp, uplift + cliff + baseLift,
                rawSample.mesaness());
    }

    /** Hoehe OHNE See-Becken und Fluss-Carving — Basis der Seespiegel-Ringpunkte (s. V2). */
    private int heightBeforeLakes(int x, int z) {
        ClimateV3 c = this.climate.sampleSmooth(x, z);
        return this.columnFor(x, z, c, BiomeWeights.blend(c), false).height;
    }

    /**
     * Lokale Hangneigung aus zentralen Differenzen der flussfreien Rohhoehe (wie V2, nur
     * im Grenzbereich der Stein-/Schneelinie aufgerufen — vier volle Spalten-Auswertungen).
     */
    private float slopeAt(int x, int z) {
        float dx = this.rawHeightAt(x + 3, z) - this.rawHeightAt(x - 3, z);
        float dz = this.rawHeightAt(x, z + 3) - this.rawHeightAt(x, z - 3);
        return Math.min((Math.abs(dx) + Math.abs(dz)) / 6F, SLOPE_MAX);
    }

    /** Rohhoehe an einer Einzelposition (Klima + Blend + Uplift selbst besorgen). */
    private float rawHeightAt(int x, int z) {
        ClimateV3 c = this.climate.sampleSmooth(x, z);
        TerrainParams p = BiomeWeights.blend(c);
        return this.rawHeight(x, z, c, p, this.upliftOffset(x, z, c),
                cliffLift(c.continentalness(), p.cliffHeight()),
                p.baseOffset() * inlandGate(c.continentalness())).h();
    }

    /**
     * Terrainhoehe OHNE Fluss-/See-Carving: Klima-Skelett (Spline + Uplift) + geblendete
     * Profil-Terme (Klippe, Grundniveau, Detail, Berge) + Intra-Biom-Shaper (Terrassen).
     * Uplift/Klippe/Grundniveau werden vom Aufrufer durchgereicht (columnFor braucht sie
     * fuer lineLift erneut — nicht doppelt rechnen).
     */
    private Raw rawHeight(int x, int z, ClimateV3 c, TerrainParams p,
                          float uplift, float cliff, float baseLift) {
        float h = continentSpline(c.continentalness());
        h += uplift;
        h += cliff;
        h += baseLift;

        /* Erosionsgesteuertes Detail, Amplitude pro Biom skaliert */
        h += this.detailNoise.GetNoise(x, z) * lerp(4F, 36F, this.ruggedness(c)) * p.detailMul();

        /* Berg-Aufschlag: Amplitude aus den Biom-Profilen (statt mountainWeight); das
         * Inland-Gate verankert die Kuestenlinie wie in V2. plateauMul verstaerkt die
         * Plateau-Kappung (Canyon-Mesas: fast immer gedeckelt statt Nadel-Grate). */
        float m = p.mountainAmp() * mountainGate(c.continentalness());
        float mesaness = 0F;
        if (m > 0.5F) {
            float ridged = (this.mountainNoise.GetNoise(x, z) + 1F) * 0.5F;
            float flatness = Math.min(1F,
                    smoothstep((this.plateauNoise.GetNoise(x, z) - 0.05F) / 0.45F) * p.plateauMul());
            float shape = lerp(ridged, Math.min(ridged * 1.25F, 0.52F), flatness);
            h += m * shape;
            /* Formanteil nur melden, wo der Bergterm real traegt (kleine Rand-Amplituden
             * daempfen — sonst bekaemen 10-Block-Huegel im Canyon-Rand schon Fels-Deckel) */
            mesaness = shape * Math.min(1F, m / 40F);
        }

        /* Canyon-Terrassen: Stufen-Shaper ueber der fertigen Hoehe (inkl. Mesa-Berge) —
         * die Steilkanten entstehen INNERHALB des Bioms, das Gewicht blendet sie nur ein */
        float terrace = terraceMix(p.terraceStrength()) * inlandGate(c.continentalness());
        if (terrace > 0.01F) {
            h = lerp(h, terrace(h), terrace);
        }

        /* Wuesten-Trockenheit: Senken auffuellen statt Tuempel (identisch zu V2) */
        float dry = desertness(c);
        if (dry > 0F) {
            h = lerp(h, Math.max(h, SEA_LEVEL + 2), dry);
        }
        return new Raw(h, mesaness);
    }

    /** Trockenheits-Faktor 0..1 (identisch zu V2, s. dort). */
    private static float desertness(ClimateV3 c) {
        return Math.min(smoothstep((c.temperature() - 0.18F) / 0.10F),
                smoothstep((-0.13F - c.humidity()) / 0.10F));
    }

    /** Kontinentalwellen-Offset (identisch zu V2, nur ClimateV3 statt Climate). */
    private float upliftOffset(int x, int z, ClimateV3 c) {
        float gate = smoothstep((c.continentalness() - Biomes.C_BEACH) / 0.25F);
        if (gate <= 0F) return 0F;
        float n = this.upliftNoise.GetNoise(x, z) + UPLIFT_BIAS;
        if (n >= 0F) return n * UPLIFT_UP * gate;
        float headroom = smoothstep((continentSpline(c.continentalness()) - 64F) / 26F);
        return n * UPLIFT_DOWN * headroom * gate;
    }

    /* ------------------------------------------------------------------ Intra-Biom-Shaper */

    /** Fjord-Klippenwand: 0 an der Strandkante, cliffHeight ab CLIFF_START+CLIFF_WIDTH. */
    private static float cliffLift(float continentalness, float cliffHeight) {
        if (cliffHeight <= 0F) return 0F;
        return cliffHeight * smoothstep((continentalness - CLIFF_START) / CLIFF_WIDTH);
    }

    /** Inland-Gate 0..1 fuer Grundniveau/Terrassen: haelt die Kuestenlinie am Meeresspiegel. */
    private static float inlandGate(float continentalness) {
        return smoothstep((continentalness - Biomes.C_BEACH) / 0.10F);
    }

    /** Inland-Gate des Berg-Aufschlags (identisch zum inland-Faktor von V2s mountainWeight). */
    private static float mountainGate(float continentalness) {
        return smoothstep((continentalness - 0.02F) / 0.30F);
    }

    /**
     * Terrassen-Funktion: Hoehe in {@link #TERRACE_STEP}-Stufen. Der komplette Hub liegt im
     * mittleren {@link #TERRACE_EDGE}-Anteil jeder Stufe (Smootherstep-Rampe; C1 an den
     * Clamp-Grenzen, weil die Ableitung dort 0 ist) — max. Steigung 1.875/TERRACE_EDGE ≈ 12×
     * Untergrund. Der fruehere doppelte Smootherstep schaffte nur 3.5×: an flachen Canyon-
     * Haengen (~0.3 Bloecke/Block) verschmierte die Kante ueber 15-25 Bloecke zur Huegelwelle.
     */
    private static float terrace(float h) {
        float u = h / TERRACE_STEP;
        float i = (float) Math.floor(u);
        float f = u - i;
        return (i + smootherstep((f - 0.5F) / TERRACE_EDGE + 0.5F)) * TERRACE_STEP;
    }

    /**
     * Terrassen-Mischanteil aus der GEBLENDETEN terraceStrength: das Parameter-Blending
     * verduennt den Profilwert selbst im Biom-Kern (Dominanz ~0.7 → aus 0.85 werden ~0.59;
     * die Trittflaechen behielten damit 41 % der Hangneigung — Huegel mit Konturlinien statt
     * Stufen). Die Remap zieht den Kern auf ~1 (ebene Tritte, voller Hub) und laesst den
     * Rand weiter stetig auf 0 auslaufen — das Hoehen-Gegenstueck zum minShare-Gate des Labels.
     */
    private static float terraceMix(float strength) {
        return smoothstep((strength - 0.15F) / 0.45F);
    }

    /* ------------------------------------------------------------------ Seen (Worley, wie V2) */

    private float lakeShoreNorm(Lake lake, int x, int z) {
        float d = (float) Math.sqrt(sq(x - lake.centerX()) + sq(z - lake.centerZ()));
        float bay = Math.max(0F, this.detailNoise.GetNoise(x * 1.9F + 713F, z * 1.9F + 713F));
        return d * (1F + bay * 0.55F) / lake.radius();
    }

    private Lake lakeAt(int x, int z) {
        Lake lake = this.lakeFor(Math.floorDiv(x, LAKE_CELL), Math.floorDiv(z, LAKE_CELL));
        if (lake == NO_LAKE) return null;
        if (sq(x - lake.centerX()) + sq(z - lake.centerZ()) > sq(lake.radius())) return null;
        return (this.lakeShoreNorm(lake, x, z) <= 1F) ? lake : null;
    }

    private Lake lakeFor(int cellX, int cellZ) {
        long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        return this.lakeCache.computeIfAbsent(key, k -> this.computeLake(cellX, cellZ));
    }

    private Lake computeLake(int cellX, int cellZ) {
        if (hash01(cellX, cellZ, this.seed, 0x5EE1) >= LAKE_CHANCE) return NO_LAKE;

        int centerX = cellX * LAKE_CELL + LAKE_CELL / 2
                + (int) ((hash01(cellX, cellZ, this.seed, 0x5EE2) * 2F - 1F) * LAKE_JITTER);
        int centerZ = cellZ * LAKE_CELL + LAKE_CELL / 2
                + (int) ((hash01(cellX, cellZ, this.seed, 0x5EE3) * 2F - 1F) * LAKE_JITTER);

        /* Nur im (feuchten) Binnenland — Canyon/Wueste bleiben per Feuchte-Gate seefrei */
        ClimateV3 c = this.climate.sampleSmooth(centerX, centerZ);
        if (c.continentalness() < Biomes.C_BEACH + 0.03F) return NO_LAKE;
        if (c.humidity() < -0.1F) return NO_LAKE;

        int radius = LAKE_RADIUS_MIN + (int) (hash01(cellX, cellZ, this.seed, 0x5EE4)
                * (LAKE_RADIUS_MAX - LAKE_RADIUS_MIN));

        int min = this.heightBeforeLakes(centerX, centerZ);
        int max = min;
        for (int i = 0; i < LAKE_RING_POINTS; i++) {
            double angle = 2 * Math.PI * i / LAKE_RING_POINTS;
            int rx = centerX + (int) (Math.cos(angle) * radius);
            int rz = centerZ + (int) (Math.sin(angle) * radius);
            int h = this.heightBeforeLakes(rx, rz);
            if (h < min) min = h;
            if (h > max) max = h;
        }
        if (max - min > LAKE_MAX_RING_SPAN) return NO_LAKE;

        return new Lake(centerX, centerZ, radius, min - 1);
    }

    /** Wasserspiegel an (x, z) — pure Funktion fuer Debug-Karten und Sonden (wie V2). */
    public int waterLevelAt(int x, int z) {
        ClimateV3 c = this.climate.sampleSmooth(x, z);
        return this.columnFor(x, z, c, BiomeWeights.blend(c), true).waterLevel;
    }

    /* ------------------------------------------------------------------ Fluss-Netz (Hooks) */

    /**
     * Leitfeld fuer den Fluss-Trace: glatte Basis (Skelett + Klippen-/Grundniveau-Lift +
     * Detail-Basisoktave) plus geblendete Berg-Amplitude als weiche Penalty — Traces laufen
     * um Massive herum. Bewusst OHNE hochfrequente Oktaven (s. RiverTerrain).
     */
    @Override
    public float riverGuide(int x, int z) {
        ClimateV3 c = this.climate.sampleSmooth(x, z);
        TerrainParams p = BiomeWeights.blend(c);
        return this.riverBase(x, z, c, p)
                + this.detailBaseNoise.GetNoise(x, z) * 0.533F
                * lerp(4F, 36F, this.ruggedness(c)) * p.detailMul()
                + p.mountainAmp() * mountainGate(c.continentalness()) * 0.35F;
    }

    /**
     * Traeger fuer das Spiegel-Profil: folgt dem echten Terrain per Detail-Oktaven 1+2
     * (wie V2). Terrassen fehlen bewusst — das laufende Minimum des Profils schneidet
     * Stufen von oben, die Rest-Abweichung (±TERRACE_STEP/2) faengt der Uferdamm.
     */
    @Override
    public float riverCarrier(int x, int z) {
        ClimateV3 c = this.climate.sampleSmooth(x, z);
        TerrainParams p = BiomeWeights.blend(c);
        return this.riverBase(x, z, c, p)
                + (this.detailBaseNoise.GetNoise(x, z) * 0.533F
                + this.detailBase2Noise.GetNoise(x, z) * 0.267F)
                * lerp(4F, 36F, this.ruggedness(c)) * p.detailMul();
    }

    /**
     * Gemeinsame glatte Basis beider Fluss-Hooks. MUSS alle Nicht-Noise-Anhebungen
     * (Klippe, Grundniveau) enthalten, sonst laege der Traeger im Fjord-Hochland pauschal
     * cliffHeight unter der Wiese und jeder Fluss wuerde dort einen Canyon carven.
     */
    private float riverBase(int x, int z, ClimateV3 c, TerrainParams p) {
        return continentSpline(c.continentalness()) + this.upliftOffset(x, z, c)
                + cliffLift(c.continentalness(), p.cliffHeight())
                + p.baseOffset() * inlandGate(c.continentalness());
    }

    /** Kontinentalitaet (glatt) — Quell-Gate des Fluss-Netzes. */
    @Override
    public float continentalnessAt(int x, int z) {
        return this.climate.sampleSmooth(x, z).continentalness();
    }

    /** Liegt (x, z) im Wasser eines Worley-Sees? Quell-Gate des Fluss-Traces. */
    @Override
    public boolean insideLake(int x, int z) {
        return this.lakeAt(x, z) != null;
    }

    /** Muendungs-Erkennung des Fluss-Traces (3x3-Zellen, Radius ohne Ufer-Noise — wie V2). */
    @Override
    public Lake lakeNear(int x, int z, int margin) {
        int cellX = Math.floorDiv(x, LAKE_CELL);
        int cellZ = Math.floorDiv(z, LAKE_CELL);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Lake lake = this.lakeFor(cellX + i, cellZ + j);
                if (lake == NO_LAKE) continue;
                long reach = lake.radius() + margin;
                if (sq(x - lake.centerX()) + sq(z - lake.centerZ()) <= reach * reach) return lake;
            }
        }
        return null;
    }

    /** Deterministischer Zell-Hash -> [0, 1) (identisch zu V2). */
    static float hash01(int x, int z, int seed, int salt) {
        int h = seed ^ salt * 0x9E3779B1;
        h ^= x * 0x85EBCA6B;
        h ^= z * 0xC2B2AE35;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return (h >>> 8) * (1F / (1 << 24));
    }

    private static long sq(long v) {
        return v * v;
    }

    /* ------------------------------------------------------------------ */

    /** Zerklueftungs-Faktor 0..1 (identisch zu V2: Erosion, kuestennah gedaempft). */
    private float ruggedness(ClimateV3 c) {
        float rugged = smoothstep((0.35F - c.erosion()) / 0.7F);
        float coastDistance = Math.abs(c.continentalness() - (Biomes.C_OCEAN + Biomes.C_BEACH) * 0.5F);
        return rugged * lerp(0.15F, 1F, smoothstep(coastDistance / 0.25F));
    }

    /** Grundhoehe ueber die Kontinentalitaet (identisch zu V2). */
    private static float continentSpline(float c) {
        if (c < -0.55F) return lerpMap(c, -1.00F, -0.55F, 10F, 28F);
        if (c < -0.45F) return lerpMap(c, -0.55F, -0.45F, 28F, 45F);
        if (c < Biomes.C_OCEAN) return lerpMap(c, -0.45F, Biomes.C_OCEAN, 45F, 62F);
        if (c < Biomes.C_BEACH) return lerpMap(c, Biomes.C_OCEAN, Biomes.C_BEACH, 62F, 67F);
        if (c < 0.30F) return lerpMap(c, Biomes.C_BEACH, 0.30F, 67F, 74F);
        return lerpMap(c, 0.30F, 1.00F, 74F, 92F);
    }

    /** Biom an Weltkoordinaten — argmax der Kernel-Gewichte (mit Grenz-Dither), threadsicher. */
    @Override
    public Biome biomeAt(int x, int z) {
        return BiomeWeights.pick(this.climate.sample(x, z));
    }

    /** Echte Terrainoberkante inkl. 3D-Verformung — Feature-Basis (Struktur wie V2). */
    @Override
    public int surfaceSolidHeight(int x, int z) {
        ClimateV3 c = this.climate.sampleSmooth(x, z);
        ColumnSample cs = this.columnFor(x, z, c, BiomeWeights.blend(c), true);
        int h2d = cs.height;
        float amp = cs.shapeAmp;
        if (amp <= 0F) return h2d;

        int top = Math.min(Chunk.HEIGHT - 2, h2d + (int) amp + 3);
        int solidBelow = Math.max(1, h2d - (int) SHAPE_AMP_CEIL - 4);

        int x0 = Math.floorDiv(x, GRID_XZ) * GRID_XZ;
        int z0 = Math.floorDiv(z, GRID_XZ) * GRID_XZ;
        float fx = (x - x0) / (float) GRID_XZ;
        float fz = (z - z0) / (float) GRID_XZ;
        int gyMin = solidBelow / GRID_Y;
        int gyMax = top / GRID_Y + 1;
        float[] layers = new float[gyMax - gyMin + 1];
        for (int gy = gyMin; gy <= gyMax; gy++) {
            layers[gy - gyMin] = this.shapeLayer(x0, z0, fx, fz, gy * GRID_Y);
        }

        for (int y = top; y > solidBelow; y--) {
            int gy = y / GRID_Y;
            float fy = (y & (GRID_Y - 1)) / (float) GRID_Y;
            float shape = lerp(layers[gy - gyMin], layers[gy - gyMin + 1], fy);
            if ((h2d - y) + shape * amp > 0F) return y;
        }
        return solidBelow;
    }

    /** Shape-Noise an einem Grid-Layer (identisch zu V2, gleiche globale Raster). */
    private float shapeLayer(int x0, int z0, float fx, float fz, int cornerY) {
        float yScaled = cornerY * 1.5F;
        float v00 = this.shapeNoise.GetNoise(x0, yScaled, z0);
        float v01 = this.shapeNoise.GetNoise(x0, yScaled, z0 + GRID_XZ);
        float v10 = this.shapeNoise.GetNoise(x0 + GRID_XZ, yScaled, z0);
        float v11 = this.shapeNoise.GetNoise(x0 + GRID_XZ, yScaled, z0 + GRID_XZ);
        return lerp(lerp(v00, v01, fz), lerp(v10, v11, fz), fx);
    }

    /** Oberflaechen-Sample fuers LOD (wie V2, Biom via BiomeWeights am gewarpten Punkt). */
    @Override
    public long sampleSurface(int x, int z) {
        ClimateV3 smooth = this.climate.sampleSmooth(x, z);
        ColumnSample cs = this.columnFor(x, z, smooth, BiomeWeights.blend(smooth), true);
        if (cs.height < cs.waterLevel) return LodDataSource.pack(Blocks.WATER, cs.waterLevel);
        int top = this.surfaceTop(x, z, cs.height, BiomeWeights.pick(this.climate.sample(x, z)),
                cs.lineLift, cs.waterLevel, cs.mesaness);
        return LodDataSource.pack(top, cs.height);
    }

    /**
     * Deckmaterial an (wx, wz) — von generate() UND LOD genutzt (geteilte Logik gegen Naehte).
     * {@code lineLift} verschiebt Stein-/Schneegrenze mit dem regionalen Grundniveau
     * (Uplift + Fjord-Klippe + Biom-Grundniveau) — sonst waeren Fjord-Plateaus und
     * Canyon-Mesas pauschal Fels/Schnee. {@code mesaness} trennt im Canyon den Sandboden
     * zwischen den Mesas vom Terracotta-Fels der Mesas.
     */
    private int surfaceTop(int wx, int wz, int height, Biome biome, float lineLift, int waterLevel,
                           float mesaness) {
        if (height < waterLevel) {
            /* Unterwasser-Boden: identische Fleckenlogik wie V2 */
            int depth = waterLevel - height;
            float n1 = this.floorNoise.GetNoise(wx, wz);
            float n2 = this.floorNoise.GetNoise(wx * 1.7F + 537F, wz * 1.7F + 537F);
            float sed = this.sedimentNoise.GetNoise(wx, wz);

            if (depth <= 3) {
                if (n1 > 0.8F - Math.max(0F, sed) * 0.5F) return Blocks.CLAY;
                if (n2 > 0.5F) return Blocks.DIRT;
                if (n1 < -0.6F + Math.max(0F, -sed) * 0.25F) return Blocks.GRAVEL;
                return Blocks.SAND;
            }
            if (depth <= 9) {
                if (n1 > 0.55F - sed * 0.3F) return Blocks.CLAY;
                if (n1 < -0.4F - sed * 0.2F) return Blocks.GRAVEL;
                return (n2 > 0.3F) ? Blocks.DIRT : Blocks.SAND;
            }
            if (n2 > 0.5F) return Blocks.SAND;
            if (n1 > 0.6F - sed * 0.25F) return Blocks.CLAY;
            if (n2 < -0.45F) return Blocks.DIRT;
            return Blocks.GRAVEL;
        }

        /* Stein-/Schneegrenze relativ zum Grundniveau, hangabhaengig (wie V2). Im Canyon
         * keine Stein-/Schneekappe: die Mesas SIND Fels (Terracotta), Schnee waere absurd. */
        if (biome != Biomes.CANYON) {
            float lineShift = lineLift + this.snowWobbleNoise.GetNoise(wx, wz) * 18F;
            int stoneLine = STONE_LINE + (int) lineShift;
            if (height >= stoneLine - SLOPE_STONE_MAX - (int) LINE_DITHER) {
                float dither = this.detailNoise.GetNoise(wx * 7.3F, wz * 7.3F) * LINE_DITHER;
                float slope = this.slopeAt(wx, wz);
                if (height >= SNOW_LINE + (int) (lineShift + slope * 5F + dither)) return Blocks.SNOW;
                if (height >= stoneLine + (int) (dither - slope * 3F)) return Blocks.STONE;
            }
        }

        /* Strandband rund um den Meeresspiegel (wie V2) */
        int beachTop = SEA_LEVEL + 2 + (int) (this.detailNoise.GetNoise(wx * 7.3F, wz * 7.3F) * 1.5F);
        if (height <= beachTop) return Blocks.SAND;

        /* Fjord-Klippenwand ragt ins Strandband-Klima: Strand-Biome ueber Strandhoehe
         * bekommen Gras statt Sand (analog zur Insel-Regel fuer OCEAN darunter) */
        if (biome == Biomes.BEACH || biome == Biomes.CARIBBEAN_BEACH) return Blocks.GRASS_BLOCK;
        if (biome == Biomes.OCEAN) return Blocks.GRASS_BLOCK;

        /* Canyon: Sandboden zwischen den Mesas (Bryce-Look), Terracotta-Fels nur dort,
         * wo der Bergterm die Spalte wirklich traegt */
        if (biome == Biomes.CANYON && mesaness < 0.30F) return Blocks.SAND;
        return biome.surfaceBlock;
    }

    /** Nur fuer Debug-Karten: Deckmaterial auch unter Wasser sichtbar (wie V2). */
    public int debugSurfaceTop(int x, int z) {
        ClimateV3 smooth = this.climate.sampleSmooth(x, z);
        ColumnSample cs = this.columnFor(x, z, smooth, BiomeWeights.blend(smooth), true);
        return this.surfaceTop(x, z, cs.height, BiomeWeights.pick(this.climate.sample(x, z)),
                cs.lineLift, cs.waterLevel, cs.mesaness);
    }

    /** Fuellmaterial unter dem Deckblock (wie V2 + Canyon-Sonderfall via biome.fillerBlock). */
    private static int fillerFor(int top, Biome biome) {
        if (top == Blocks.SAND) return Blocks.SANDSTONE;
        if (top == Blocks.GRAVEL || top == Blocks.CLAY || top == Blocks.DIRT) return top;
        if (top == Blocks.SNOW || top == Blocks.STONE) return Blocks.STONE;
        if (top == Blocks.GRASS_BLOCK) return Blocks.DIRT;
        return biome.fillerBlock;
    }

    @Override
    public void generate(Chunk chunk) {
        long start = System.nanoTime();
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
        final int size = ChunkSection.SIZE;

        /* 1) Spaltendaten (Struktur wie V2, plus Canyon-Strata-Flag) */
        int[] heights = new int[size * size];
        int[] tops = new int[size * size];
        int[] fillers = new int[size * size];
        int[] waterLevels = new int[size * size];
        float[] shapeAmps = new float[size * size];
        float[] lineLifts = new float[size * size];
        float[] mesanesses = new float[size * size];
        Biome[] biomes = new Biome[size * size];
        int maxH = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int wx = baseX + x, wz = baseZ + z;
                int i = x * size + z;

                ClimateV3 smooth = this.climate.sampleSmooth(wx, wz);
                ColumnSample cs = this.columnFor(wx, wz, smooth, BiomeWeights.blend(smooth), true);
                int h = cs.height;
                /* Biom-Klassifikation am gewarpten Punkt (bit-identisch zu biomeAt);
                 * Terrain-Blend oben bleibt auf dem Smooth-Sample der echten Position */
                Biome biome = BiomeWeights.pick(this.climate.sample(wx, wz));
                heights[i] = h;
                biomes[i] = biome;
                waterLevels[i] = cs.waterLevel;
                lineLifts[i] = cs.lineLift;
                mesanesses[i] = cs.mesaness;
                tops[i] = this.surfaceTop(wx, wz, h, biome, lineLifts[i], cs.waterLevel, cs.mesaness);
                fillers[i] = fillerFor(tops[i], biome);
                shapeAmps[i] = cs.shapeAmp;
                if (h > maxH) maxH = h;
            }
        }

        /* 2) 3D-Noise nur an Gitterpunkten (identisch zu V2) */
        int yTop = Math.min(Chunk.HEIGHT - 2, maxH + (int) SHAPE_AMP_CEIL + 4);
        int gridsXZ = size / GRID_XZ + 1;
        int gridsY = yTop / GRID_Y + 2;
        float[] shapeGrid = new float[gridsY * gridsXZ * gridsXZ];
        float[] cheeseGrid = new float[gridsY * gridsXZ * gridsXZ];
        float[] sp1Grid = new float[gridsY * gridsXZ * gridsXZ];
        float[] sp2Grid = new float[gridsY * gridsXZ * gridsXZ];
        float[] stone1Grid = new float[gridsY * gridsXZ * gridsXZ];
        float[] stone2Grid = new float[gridsY * gridsXZ * gridsXZ];
        for (int gy = 0; gy < gridsY; gy++) {
            float y = gy * GRID_Y;
            for (int gx = 0; gx < gridsXZ; gx++) {
                float wx = baseX + gx * GRID_XZ;
                for (int gz = 0; gz < gridsXZ; gz++) {
                    float wz = baseZ + gz * GRID_XZ;
                    int gi = (gy * gridsXZ + gx) * gridsXZ + gz;
                    shapeGrid[gi] = this.shapeNoise.GetNoise(wx, y * 1.5F, wz);
                    cheeseGrid[gi] = this.cheeseNoise.GetNoise(wx, y, wz);
                    sp1Grid[gi] = this.spaghettiNoise1.GetNoise(wx, y, wz);
                    sp2Grid[gi] = this.spaghettiNoise2.GetNoise(wx, y, wz);
                    stone1Grid[gi] = this.stoneNoise1.GetNoise(wx, y, wz);
                    stone2Grid[gi] = this.stoneNoise2.GetNoise(wx, y, wz);
                }
            }
        }

        /* 3) Spalten fuellen (Struktur wie V2; Canyon-Spalten: Terracotta-Strata statt Stein) */
        float[] colShape = new float[gridsY];
        float[] colCheese = new float[gridsY];
        float[] colSp1 = new float[gridsY];
        float[] colSp2 = new float[gridsY];
        float[] colStone1 = new float[gridsY];
        float[] colStone2 = new float[gridsY];
        boolean[] solid = new boolean[yTop + 1];

        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int i = x * size + z;
                int h2d = heights[i];
                float amp = shapeAmps[i];

                bilinearColumn(shapeGrid, gridsXZ, gridsY, x, z, colShape);
                bilinearColumn(cheeseGrid, gridsXZ, gridsY, x, z, colCheese);
                bilinearColumn(sp1Grid, gridsXZ, gridsY, x, z, colSp1);
                bilinearColumn(sp2Grid, gridsXZ, gridsY, x, z, colSp2);
                bilinearColumn(stone1Grid, gridsXZ, gridsY, x, z, colStone1);
                bilinearColumn(stone2Grid, gridsXZ, gridsY, x, z, colStone2);

                int waterLevel = waterLevels[i];
                boolean protectFloor = h2d < waterLevel + 2;
                int colTop = Math.min(yTop, h2d + (int) amp + 3);

                int topSolid = 0;
                for (int y = colTop; y >= 1; y--) {
                    boolean isSolid;
                    if (y <= h2d - (int) SHAPE_AMP_CEIL - 4 || amp <= 0F) {
                        isSolid = y <= h2d;
                    } else {
                        isSolid = (h2d - y) + lerpColumn(colShape, y) * amp > 0F;
                    }

                    if (isSolid && y > 6 && y < h2d - 6 && !(protectFloor && y > h2d - 12)) {
                        if (lerpColumn(colCheese, y) > CHEESE_THRESHOLD
                                || (Math.abs(lerpColumn(colSp1, y)) < SPAGHETTI_THRESHOLD
                                && Math.abs(lerpColumn(colSp2, y)) < SPAGHETTI_THRESHOLD)) {
                            isSolid = false;
                        }
                    }

                    solid[y] = isSolid;
                    if (isSolid && topSolid == 0) topSolid = y;
                }

                /* Ufersaum-Korrektur (wie V2) */
                int top = tops[i];
                int filler = fillers[i];
                if (topSolid >= waterLevel && h2d < waterLevel) {
                    top = this.surfaceTop(baseX + x, baseZ + z, topSolid, biomes[i], lineLifts[i],
                            waterLevel, mesanesses[i]);
                    filler = fillerFor(top, biomes[i]);
                }

                float fillerNoise = this.detailNoise.GetNoise((baseX + x) * 3.1F, (baseZ + z) * 3.1F);
                int fillerDepth = Math.clamp(1 + (int) ((fillerNoise + 1F) * 1.7F), 1, 4);

                /* Canyon-Strata: gewellte Terracotta-Baender nach absoluter Hoehe — die
                 * Baender liegen im Stein-Bereich der Spalte und werden erst an
                 * freigelegten Mesa-Waenden sichtbar. Versatz einmal pro Spalte. */
                boolean strata = biomes[i] == Biomes.CANYON;
                int strataShift = strata
                        ? (int) (this.sedimentNoise.GetNoise((baseX + x) * 2.2F + 913F, (baseZ + z) * 2.2F + 913F) * 7F)
                        : 0;

                chunk.setBlock(x, 0, z, Blocks.BEDROCK);
                for (int y = 1; y <= topSolid; y++) {
                    if (!solid[y]) continue;
                    int block;
                    if (y == topSolid) block = top;
                    else if (y >= topSolid - fillerDepth) block = filler;
                    else if (strata && y > STRATA_MIN_Y) block = canyonStratum(y + strataShift);
                    else block = stoneAt(colStone1, colStone2, y);
                    chunk.setBlock(x, y, z, block);
                }

                for (int y = topSolid + 1; y <= waterLevel; y++) {
                    chunk.setBlock(x, y, z, Blocks.WATER);
                }

                if (topSolid >= waterLevel && topSolid + 3 < Chunk.HEIGHT) {
                    this.placePlants(chunk, x, z, baseX + x, baseZ + z, topSolid, top, biomes[i]);
                }
            }
        }

        this.buildTintGrids(chunk, baseX, baseZ);

        this.trackGenerateTime(System.nanoTime() - start);
    }

    /**
     * Terracotta-Schicht nach (versetzter) absoluter Hoehe: sich wiederholende Bandfolge
     * wie in Mesa-Landschaften. Bewusst eine Methode statt eines statischen Arrays —
     * ein Array wuerde {@code Blocks.*}-IDs beim Klassen-Init einfangen (Init-Falle:
     * der Generator entsteht VOR Blocks.bootstrap).
     */
    private static int canyonStratum(int y) {
        int band = Math.floorMod(y, 26);
        if (band < 4) return Blocks.TERRACOTTA;
        if (band < 6) return Blocks.ORANGE_TERRACOTTA;
        if (band < 8) return Blocks.RED_TERRACOTTA;
        if (band < 11) return Blocks.TERRACOTTA;
        if (band < 13) return Blocks.YELLOW_TERRACOTTA;
        if (band < 14) return Blocks.WHITE_TERRACOTTA;
        if (band < 17) return Blocks.ORANGE_TERRACOTTA;
        if (band < 19) return Blocks.BROWN_TERRACOTTA;
        if (band < 22) return Blocks.TERRACOTTA;
        if (band < 23) return Blocks.LIGHT_GRAY_TERRACOTTA;
        return Blocks.ORANGE_TERRACOTTA;
    }

    /* Biome-Tint-Glaettung: identisch zu V2, Biome via BiomeWeights.pick */
    private static final int TINT_STEP = 4;
    private static final int TINT_COARSE = 13;
    private static final int TINT_SMOOTH = 11;

    /** Berechnet die 33x33-Eck-Farbgrids des Chunks (pure Funktionswerte, wie V2). */
    private void buildTintGrids(Chunk chunk, int baseX, int baseZ) {
        int[] coarseGrass = new int[TINT_COARSE * TINT_COARSE];
        int[] coarseFoliage = new int[TINT_COARSE * TINT_COARSE];
        for (int i = 0; i < TINT_COARSE; i++) {
            for (int j = 0; j < TINT_COARSE; j++) {
                int wx = baseX + (i - 2) * TINT_STEP;
                int wz = baseZ + (j - 2) * TINT_STEP;
                Biome biome = BiomeWeights.pick(this.climate.sample(wx, wz));
                coarseGrass[i * TINT_COARSE + j] = biome.grassTint;
                coarseFoliage[i * TINT_COARSE + j] = biome.foliageTint;
            }
        }

        int[] smoothGrass = new int[TINT_SMOOTH * TINT_SMOOTH];
        int[] smoothFoliage = new int[TINT_SMOOTH * TINT_SMOOTH];
        for (int i = 0; i < TINT_SMOOTH; i++) {
            for (int j = 0; j < TINT_SMOOTH; j++) {
                smoothGrass[i * TINT_SMOOTH + j] = boxAverage(coarseGrass, i + 1, j + 1);
                smoothFoliage[i * TINT_SMOOTH + j] = boxAverage(coarseFoliage, i + 1, j + 1);
            }
        }

        int corners = ChunkSection.SIZE + 1;
        int[] grass = new int[corners * corners];
        int[] foliage = new int[corners * corners];
        for (int cx = 0; cx < corners; cx++) {
            int k = (cx + TINT_STEP) / TINT_STEP;
            float fx = (cx & (TINT_STEP - 1)) / (float) TINT_STEP;
            for (int cz = 0; cz < corners; cz++) {
                int l = (cz + TINT_STEP) / TINT_STEP;
                float fz = (cz & (TINT_STEP - 1)) / (float) TINT_STEP;
                grass[cx * corners + cz] = bilerpColor(smoothGrass, TINT_SMOOTH, k, l, fx, fz);
                foliage[cx * corners + cz] = bilerpColor(smoothFoliage, TINT_SMOOTH, k, l, fx, fz);
            }
        }
        chunk.grassTintCorners = grass;
        chunk.foliageTintCorners = foliage;
    }

    /** LOD-Grasfarbe: pures Biom-Sample am (gewarpten) Punkt — konsistent zur L0-Tint-Grenzlinie. */
    @Override
    public int grassTintAt(int x, int z) {
        return BiomeWeights.pick(this.climate.sample(x, z)).grassTint;
    }

    @Override
    public int foliageTintAt(int x, int z) {
        return BiomeWeights.pick(this.climate.sample(x, z)).foliageTint;
    }

    /** Mittel der 3x3-Nachbarschaft im Grobraster (identisch zu V2). */
    private static int boxAverage(int[] coarse, int ci, int cj) {
        int r = 0, g = 0, b = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                int c = coarse[(ci + di) * TINT_COARSE + (cj + dj)];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
        }
        return (r / 9 << 16) | (g / 9 << 8) | (b / 9);
    }

    /** Bilineare Farb-Interpolation (identisch zu V2). */
    private static int bilerpColor(int[] grid, int stride, int k, int l, float fx, float fz) {
        int c00 = grid[k * stride + l], c01 = grid[k * stride + l + 1];
        int c10 = grid[(k + 1) * stride + l], c11 = grid[(k + 1) * stride + l + 1];
        int r = (int) lerp(lerp((c00 >> 16) & 0xFF, (c01 >> 16) & 0xFF, fz), lerp((c10 >> 16) & 0xFF, (c11 >> 16) & 0xFF, fz), fx);
        int g = (int) lerp(lerp((c00 >> 8) & 0xFF, (c01 >> 8) & 0xFF, fz), lerp((c10 >> 8) & 0xFF, (c11 >> 8) & 0xFF, fz), fx);
        int b = (int) lerp(lerp(c00 & 0xFF, c01 & 0xFF, fz), lerp(c10 & 0xFF, c11 & 0xFF, fz), fx);
        return (r << 16) | (g << 8) | b;
    }

    /** Bodenpflanzen (wie V2: Dichtefeld x Pro-Block-Hash; Kakteen in Wueste UND Canyon-Boden). */
    private void placePlants(Chunk chunk, int x, int z, int wx, int wz, int topSolid, int top, Biome biome) {
        float density = 0.25F + 0.75F * (this.vegNoise.GetNoise(wx, wz) + 1F) * 0.5F;

        if ((biome == Biomes.DESERT || biome == Biomes.CANYON) && top == Blocks.SAND) {
            if (hash01(wx, wz, this.seed, 0xCAC7) < 0.004F * density) {
                int height = 1 + (int) (hash01(wx, wz, this.seed, 0xCAC8) * 3F);
                for (int i = 1; i <= height; i++) {
                    chunk.setBlock(x, topSolid + i, z, Blocks.CACTUS);
                }
                return;
            }
        }

        /* Canyon: tote Buesche auch auf dem Sandboden zwischen den Mesas —
         * surfaceBlock des Bioms ist der Mesa-Fels (RED_SANDSTONE) */
        boolean canyonFloor = biome == Biomes.CANYON && top == Blocks.SAND;
        if ((top != biome.surfaceBlock && !canyonFloor) || biome.plants.length == 0) return;
        if (hash01(wx, wz, this.seed, 0x9EA7) >= biome.plantDensity * density) return;

        int total = 0;
        for (Biome.PlantEntry plant : biome.plants) total += plant.weight();
        int pick = (int) (hash01(wx, wz, this.seed, 0x9EA8) * total);
        for (Biome.PlantEntry plant : biome.plants) {
            pick -= plant.weight();
            if (pick < 0) {
                chunk.setBlock(x, topSolid + 1, z, plant.blockId());
                BlockState state = BlockRegistry.getState(plant.blockId());
                if (state.getValues().containsKey(Properties.HALF)) {
                    chunk.setBlock(x, topSolid + 2, z, state.with(Properties.HALF, BlockHalf.TOP).getId());
                }
                return;
            }
        }
    }

    /** Gesteinsvariante im Untergrund (identisch zu V2). */
    private static int stoneAt(float[] colStone1, float[] colStone2, int y) {
        float n1 = lerpColumn(colStone1, y);
        if (n1 > STONE_VEIN_THRESHOLD) return Blocks.GRANITE;
        if (n1 < -STONE_VEIN_THRESHOLD) return Blocks.DIORITE;
        if (lerpColumn(colStone2, y) > STONE_VEIN_THRESHOLD) return Blocks.ANDESITE;
        return Blocks.STONE;
    }

    /** Bilineare Interpolation eines Noise-Grids auf die Block-Spalte (identisch zu V2). */
    private static void bilinearColumn(float[] grid, int gridsXZ, int gridsY, int x, int z, float[] out) {
        int gx = x / GRID_XZ, gz = z / GRID_XZ;
        float fx = (x & (GRID_XZ - 1)) / (float) GRID_XZ;
        float fz = (z & (GRID_XZ - 1)) / (float) GRID_XZ;
        for (int gy = 0; gy < gridsY; gy++) {
            int base = (gy * gridsXZ + gx) * gridsXZ + gz;
            float v00 = grid[base], v01 = grid[base + 1];
            float v10 = grid[base + gridsXZ], v11 = grid[base + gridsXZ + 1];
            out[gy] = lerp(lerp(v00, v01, fz), lerp(v10, v11, fz), fx);
        }
    }

    /** Linearer y-Anteil der trilinearen Interpolation (identisch zu V2). */
    private static float lerpColumn(float[] column, int y) {
        int gy = y / GRID_Y;
        float fy = (y & (GRID_Y - 1)) / (float) GRID_Y;
        return lerp(column[gy], column[gy + 1], fy);
    }

    private void trackGenerateTime(long nanos) {
        long total = this.generateNanos.addAndGet(nanos);
        int count = this.generateCount.incrementAndGet();
        if ((count & 255) == 0) {
            this.logger.debug(String.format("Generierung: %.2f ms/Chunk (Schnitt ueber %d Chunks)",
                    total / (double) count / 1_000_000.0, count));
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Bildet value aus [inMin, inMax] linear (geklemmt) auf [outMin, outMax] ab. */
    private static float lerpMap(float value, float inMin, float inMax, float outMin, float outMax) {
        float t = (value - inMin) / (inMax - inMin);
        if (t <= 0F) return outMin;
        if (t >= 1F) return outMax;
        return outMin + (outMax - outMin) * t;
    }

    private static float smoothstep(float t) {
        if (t <= 0F) return 0F;
        if (t >= 1F) return 1F;
        return t * t * (3F - 2F * t);
    }

    /** Quintisches Smootherstep (flachere Enden als smoothstep — fuer die Terrassen-Kanten). */
    private static float smootherstep(float t) {
        if (t <= 0F) return 0F;
        if (t >= 1F) return 1F;
        return t * t * t * (t * (t * 6F - 15F) + 10F);
    }
}
