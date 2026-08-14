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
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.generator.climate.Climate;
import de.skyengine.game.world.generator.climate.ClimateSampler;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.utils.math.FastNoiseLite;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Klimabasierter Generator (Phase 3): Hoehe und Biome werden aus denselben kontinuierlichen
 * Klimafeldern ({@link ClimateSampler}) abgeleitet — Biomuebergaenge sind dadurch automatisch
 * glatt, ohne Parameter-Blending an Biomgrenzen.
 *
 * <p>Seed-Offsets werden zentral in {@link de.skyengine.game.world.generator.WorldgenSeeds}
 * vergeben (ClimateSampler 0..8, eigene Noises 10..23, {@link RiverNetwork} 24/25).
 */
public class AlphaWorldGeneratorV2 extends WorldGenerator {

    /** Generator-Version fuer den Save-Header (level.json) — bei bit-brechenden Aenderungen erhoehen. */
    public static final int VERSION = 1;

    /* Meeresspiegel: bis zu dieser Hoehe wird Wasser aufgefuellt */
    static final int SEA_LEVEL = 64;
    /* Maximaler Berg-Aufschlag (skaliert mit Biomes.mountainWeight) -> Gipfel bis ~260 */
    private static final float MOUNTAIN_AMP = 170F;
    /* Ab dieser Hoehe: Fels statt Biomdecke */
    private static final int STONE_LINE = 125;
    /* Ab dieser Hoehe: Schneekappe */
    private static final int SNOW_LINE = 160;
    /* Verwackelung der Hoehenlinien, damit keine geraden Konturen entstehen (wie V1) */
    private static final float LINE_DITHER = 10F;
    /* Hangneigung wird auf diesen Wert gekappt; die Steinlinie sinkt damit um max. 3*8=24 —
     * der Wert steckt auch im Gate, ab dem der (teure) Neigungs-Test ueberhaupt laeuft */
    private static final int SLOPE_MAX = 8;
    private static final int SLOPE_STONE_MAX = SLOPE_MAX * 3;

    /* 3D-Dichte: Noise nur an Gitterpunkten (4 Bloecke horizontal, 8 vertikal) samplen und
     * pro Block trilinear interpolieren — ~2.500 statt ~250.000 3D-Evals pro Chunk */
    private static final int GRID_XZ = 4;
    private static final int GRID_Y = 8;
    /* Maximale 3D-Verformung der Oberflaeche (Ueberhaenge/Klippen) in zerklueftetem Terrain */
    private static final float SHAPE_AMP_MAX = 12F;
    /* Kammer-Hoehlen (Cheese) ab diesem Noise-Wert */
    private static final float CHEESE_THRESHOLD = 0.62F;
    /* Tunnel (Spaghetti): beide Noises gleichzeitig nahe null */
    private static final float SPAGHETTI_THRESHOLD = 0.06F;
    /* Gesteins-Adern: Noise-Extreme werden zu Diorit/Granit/Andesit-Blasen im Stein */
    private static final float STONE_VEIN_THRESHOLD = 0.62F;

    /* Lokale Seen auf einem Worley-Zellraster: pro Zelle hoechstens ein Kandidatenpunkt.
     * Jitter+Radius sind so gewaehlt, dass ein See seine Zelle NIE verlaesst
     * (Zentrum 192+-100, Radius <=90 -> Wasser in [2, 382]): keine Ueberlappung zweier
     * Wasserspiegel, und pro Spalte genuegt der Blick in die EIGENE Zelle. */
    private static final int LAKE_CELL = 384;
    private static final int LAKE_JITTER = 100;
    private static final int LAKE_RADIUS_MIN = 40;
    private static final int LAKE_RADIUS_MAX = 90;
    private static final float LAKE_CHANCE = 0.35F;
    /* Beckentiefe unter dem Seespiegel; Ringpunkte fuer Spiegel/Hang-Gate — auf dem VOLLEN
     * Radius (maximale Wasserausdehnung) und dicht genug, dass hangseitig kein Gelaende
     * zwischen zwei Punkten unter den Spiegel rutscht (sonst Wasserwand am See-Rand) */
    private static final int LAKE_DEPTH = 5;
    private static final int LAKE_RING_POINTS = 16;
    private static final int LAKE_MAX_RING_SPAN = 12;

    /* Kontinentalwellen: Hebung deutlich staerker als Senkung (Bias + getrennte Skalen),
     * und Senkung zusaetzlich nach Spline-Headroom gedrosselt — sonst fluten die Wellen
     * die grossen base-67-Ebenen und die Welt zerfaellt zum Archipel */
    private static final float UPLIFT_UP = 75F;
    private static final float UPLIFT_DOWN = 45F;
    private static final float UPLIFT_BIAS = 0.25F;

    private final ClimateSampler climate;
    /* Kontinentalwellen: sehr niederfrequente Hebung/Senkung ganzer Regionen (~12k Bloecke),
     * damit die Welt aus LOD-Distanz nicht wie eine flache Scheibe wirkt */
    private final FastNoiseLite upliftNoise;
    /* Bergform-Vielfalt: langsam wechselndes Noise, das lokal die Ridged-Schaerfe kappt —
     * statt durchgehend spitzer Grate entstehen Hochebenen, breite Ruecken und offene Taeler */
    private final FastNoiseLite plateauNoise;
    /* Grossraeumige Welligkeit der Stein-/Schneegrenze: das kleinraeumige LINE_DITHER
     * verschwindet aus LOD-Distanz optisch, dieses Noise haelt die Grenze auch fern wellig */
    private final FastNoiseLite snowWobbleNoise;
    /* Lokales Terrain-Detail; Amplitude skaliert mit der Erosion (glatt vs. zerklueftet) */
    private final FastNoiseLite detailNoise;
    /* Die ersten BEIDEN Oktaven des Detail-Noise als Einzel-Instanzen (Oktave i des FBm =
     * GenNoiseSingle(seed+i-1, coords*f*2^(i-1)), Gewichte 0.533/0.267): Traeger und Leitfeld
     * des Fluss-Netzes ({@link #riverCarrier}/{@link #riverGuide}) folgen damit dem echten
     * Terrain bis auf die kleinen Oktaven 3+4 (~±0.6 in Ebenen) — nur so liegt das monotone
     * Spiegel-Profil verlaesslich UNTER der Wiese. detailBase2 teilt den Basis-Seed mit
     * mountainNoise (seed+11), aber andere Frequenz/Nutzung -> keine sichtbare Korrelation. */
    private final FastNoiseLite detailBaseNoise;
    private final FastNoiseLite detailBase2Noise;
    /* Berg-Form: Ridged-Fraktal fuer Grate und Gipfel */
    private final FastNoiseLite mountainNoise;
    /* Kleinraeumige Materialflecken fuer Ozean-/Flussboeden (Ton/Kies/Sand) */
    private final FastNoiseLite floorNoise;
    /* Regionale Sediment-Charakteristik (~600-1000 Bloecke, grob ein Flussabschnitt bzw.
     * Meeresgebiet): verschiebt die floorNoise-Schwellen — manche Fluesse/Meere ton-reich,
     * andere kiesig oder fast rein sandig. Zweites (versetztes) Sample variiert die Flusstiefe. */
    private final FastNoiseLite sedimentNoise;
    /* Bodenpflanzen-DICHTEFELD: weiche 0..1-Verteilung ueber ~150-250 Bloecke; ob eine
     * einzelne Spalte bewachsen ist, entscheidet ein Pro-Block-Hash gegen dieses Feld —
     * keine binaeren Bewuchs-Klumpen mehr */
    private final FastNoiseLite vegNoise;
    /* 3D-Verformung der Oberflaeche (Ueberhaenge, Boegen, unregelmaessige Klippen) */
    private final FastNoiseLite shapeNoise;
    /* Hoehlen: grosse Kammern (Cheese) + zwei unabhaengige Tunnel-Noises (Spaghetti) */
    private final FastNoiseLite cheeseNoise;
    private final FastNoiseLite spaghettiNoise1;
    private final FastNoiseLite spaghettiNoise2;
    /* Gesteinsvarianten im Untergrund: Noise 1 -> Granit (+)/Diorit (-), Noise 2 -> Andesit.
     * Zwei getrennte glatte Noises statt Mittelband auf einem — ein Mittelband ergaebe
     * Schalen um jede Granit-/Diorit-Blase statt eigener Adern. */
    private final FastNoiseLite stoneNoise1;
    private final FastNoiseLite stoneNoise2;

    /* See-Zellen-Cache: Spiegel/Radius pro Worley-Zelle sind pure Funktionswerte, aber ihre
     * Berechnung sampelt 9 Ring-Hoehen — einmal pro Zelle rechnen statt pro Spalte.
     * ConcurrentHashMap, weil mehrere Worker-Threads parallel generieren. */
    private final ConcurrentHashMap<Long, Lake> lakeCache = new ConcurrentHashMap<>();

    /* Quelle-zu-Muendung-Flussnetz (eigene Zellen-Memoization, s. RiverNetwork) */
    private final RiverNetwork riverNetwork;

    /** Ein See: fester Spiegel pro See (flach!), Becken wird unter den Spiegel gecarvt. */
    record Lake(int centerX, int centerZ, int radius, int level) {
    }

    /** Zellen-Sentinel fuer "kein See" (Cache kann kein null speichern). */
    private static final Lake NO_LAKE = new Lake(0, 0, 0, 0);

    /* Generierungszeit-Statistik (threadsicher, Log alle 256 Chunks) */
    private final AtomicLong generateNanos = new AtomicLong();
    private final AtomicInteger generateCount = new AtomicInteger();
    private final Logger logger = LogManager.getLogger(AlphaWorldGeneratorV2.class.getName());

    public AlphaWorldGeneratorV2(int seed) {
        super(seed);
        this.climate = new ClimateSampler(seed);

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
        return this.heightFor(x, z, this.climate.sampleSmooth(x, z));
    }

    private int heightFor(int x, int z, Climate c) {
        return this.columnFor(x, z, c, true).height;
    }

    /** Ergebnis der Spaltenberechnung: Hoehe, lokaler Wasserspiegel, 3D-Amplitude, Uplift. */
    private record ColumnSample(int height, int waterLevel, float shapeAmp, float uplift) {
    }

    /**
     * Berechnet eine Terrainspalte komplett: Rohhoehe, Fluss- und See-Carving, den lokalen
     * Wasserspiegel (Meer, Fluss oder See) und die 3D-Amplitude. Fluss- und Seebecken
     * daempfen die 3D-Verformung auf 0 — sonst hebt das Shape-Noise die Sohle stellenweise
     * ueber den Wasserspiegel (trockene Flussbett-Flicken).
     *
     * <p>Fluesse kommen aus dem {@link RiverNetwork}: explizite Laeufe mit monoton
     * fallendem Spiegel-Profil von der Quelle bis Meer/See/Endbecken. Das Terrain wird
     * um den Lauf geformt (Kanal carven, lokale Senken als Uferdamm auffuellen) statt
     * umgekehrt — Gates und Klammern des alten Iso-Linien-Modells entfallen.
     *
     * <p>{@code withWater=false} rechnet die reine Terrainform OHNE See- und Fluss-Carving:
     * Basis der Seespiegel-Ringpunkte. Seen duerfen dabei nicht von Fluessen abhaengen,
     * sonst entsteht eine Cache-Rekursion (See-Ring -> Fluss-Trace -> Muendungs-See).
     */
    private ColumnSample columnFor(int x, int z, Climate c, boolean withWater) {
        /* Uplift einmal berechnen und durchreichen — generate()/sampleSurface brauchen ihn
           fuer die Stein-/Schneegrenze erneut (frueher doppelt gerechnet). */
        float uplift = this.upliftOffset(x, z, c);
        float raw = this.rawHeight(x, z, c, uplift);
        float h = raw;
        int waterLevel = SEA_LEVEL;
        int riverWater = Integer.MIN_VALUE;
        float damp = 1F;

        /* Fluss: Kanal + Tal um die Polylinie des Netzes formen */
        if (withWater) {
            RiverNetwork.Sample river = this.riverNetwork.sampleAt(x, z);
            if (river != null) {
                float spiegel = river.surf();
                float valleyHalf = river.half() * RiverNetwork.VALLEY_FACTOR;
                float shoulder = valleyHalf * RiverNetwork.SHOULDER_FACTOR;
                /* Uferdamm: lokale Senken unterm Spiegel werden bis Spiegel+1 aufgefuellt
                 * (gedeckelt, nach aussen ausgeblendet) — die einzige Terrain-ANHEBUNG im
                 * Fluss-System. Noetig, weil das monotone Profil lokalen Terrain-Dips
                 * nicht folgen darf; die Dips sind konstruktionsbedingt flach (Profil
                 * liegt an jedem Knoten unter Traeger−3, Rest-Oktaven ±~4). */
                if (raw < spiegel + 1F) {
                    float lift = Math.min(4F, spiegel + 1F - raw);
                    float fade = Math.min(1F, (shoulder - river.dist()) / (shoulder - valleyHalf));
                    h = Math.max(h, raw + lift * smoothstep(fade));
                }
                if (river.dist() < valleyHalf) {
                    /* 0 am Talrand .. 1 im Kanal; Saettigung invers zur Kanalbreite:
                     * schmale Laeufe = steile Kerbe, breite = flacher Ufersaum;
                     * Untergrenze 1.1 haelt die Sohle ueberall voll gecarvt */
                    float t = smoothstep(1F - river.dist() / valleyHalf);
                    float carve = Math.min(1F, t * Math.clamp(13F / river.half(), 1.1F, 2.4F));
                    /* Steile Hangquerungen: faellt das Terrain quer zum Kanal schneller ab,
                     * als der Uferdamm auffuellt, folgt der Spiegel dem Gelaende in Stufen
                     * (Bett relativ dazu -> Kanal bleibt nass) statt als Wand zu stehen;
                     * max. 2 ueber raw wie beim 3a-Klammerwert */
                    float effWater = Math.min(spiegel, raw + 2F);
                    /* Betttiefe: regionale Variation (versetztes Sediment-Sample) plus
                     * Breiten-Kopplung — normale Laeufe ~5-8 tief, breite bis ~10,
                     * Quell-Baeche flach (Kopplung erst ab Halbbreite 4) */
                    float depthVar = (this.sedimentNoise.GetNoise(x * 1.3F + 557F, z * 1.3F + 557F) + 1F) * 0.5F;
                    float bed = effWater - 2F - depthVar * 2F - Math.min(6F, Math.max(0F, river.half() - 4F) * 0.7F);
                    if (h > bed) h = lerp(h, bed, carve);
                    damp *= 1F - carve;
                    riverWater = (int) effWater;
                }
            }
        }

        /* See: Becken glockenfoermig unter den Spiegel carven — Becken garantiert unterm
         * Wasser, unberuehrter Rand garantiert darueber (Bergsee-faehig) */
        if (withWater) {
            Lake lake = this.lakeAt(x, z);
            if (lake != null) {
                float t = Math.min(1F, smoothstep(1F - this.lakeShoreNorm(lake, x, z)) * 1.3F);
                float target = lake.level - LAKE_DEPTH;
                if (h > target) h = lerp(h, target, t);
                damp *= 1F - t;
                waterLevel = Math.max(waterLevel, lake.level);
                /* Fluss muendet in den See: der Flussspiegel folgt dem gecarvten Becken
                 * in 1-2-Block-Stufen bis auf Seehoehe hinab, statt als Wasserruecken
                 * ueber dem Ufer zu stehen */
                riverWater = Math.min(riverWater, Math.max(lake.level, (int) h + 2));
            }
        }
        waterLevel = Math.max(waterLevel, riverWater);

        /* Untergrenze knapp ueber Bedrock: extreme Senkung+Detail darf nicht unter 0 laufen */
        int height = Math.clamp((int) h, 8, Chunk.HEIGHT - 2);
        return new ColumnSample(height, waterLevel, SHAPE_AMP_MAX * this.ruggedness(c) * damp, uplift);
    }

    /**
     * Hoehe OHNE See-Becken und OHNE Fluss-Carving — Basis fuer die Seespiegel-Berechnung
     * an den Ringpunkten (der Spiegel eines Sees darf weder vom eigenen Becken noch von
     * Fluessen abhaengen: Cache-Rekursion, s. {@link #columnFor}).
     */
    private int heightBeforeLakes(int x, int z) {
        return this.columnFor(x, z, this.climate.sampleSmooth(x, z), false).height;
    }

    /**
     * Lokale Hangneigung (Bloecke Hoehendifferenz pro Block, gekappt auf {@link #SLOPE_MAX}),
     * aus zentralen Differenzen der flussfreien Rohhoehe. Bewusst NUR im Grenzbereich der
     * Stein-/Schneelinie aufgerufen — vier zusaetzliche Klima-Samples pro Aufruf.
     */
    private float slopeAt(int x, int z) {
        float dx = this.rawHeight(x + 3, z, this.climate.sampleSmooth(x + 3, z))
                - this.rawHeight(x - 3, z, this.climate.sampleSmooth(x - 3, z));
        float dz = this.rawHeight(x, z + 3, this.climate.sampleSmooth(x, z + 3))
                - this.rawHeight(x, z - 3, this.climate.sampleSmooth(x, z - 3));
        return Math.min((Math.abs(dx) + Math.abs(dz)) / 6F, SLOPE_MAX);
    }

    /** Terrainhoehe OHNE Fluss-Carving — Basis von {@link #heightFor} und dem Hangneigungs-Test. */
    private float rawHeight(int x, int z, Climate c) {
        return this.rawHeight(x, z, c, this.upliftOffset(x, z, c));
    }

    /** Wie {@link #rawHeight(int, int, Climate)}, mit bereits berechnetem Uplift (Hot-Path). */
    private float rawHeight(int x, int z, Climate c, float uplift) {
        /* Grundform aus der Kontinentalitaet: Tiefsee -> Kueste -> Landesinneres */
        float h = continentSpline(c.continentalness());

        /* Kontinentalwellen heben/senken das regionale Grundniveau (an der Kueste 0) */
        h += uplift;

        /* Erosion moduliert das lokale Detail: glatte Ebenen vs. schroffe Huegel */
        h += this.detailNoise.GetNoise(x, z) * lerp(4F, 36F, this.ruggedness(c));

        /* Berg-Aufschlag nur, wo das Gebirgs-Gewicht wirkt (deckungsgleich mit EXTREME_HILLS).
         * Das Plateau-Noise kappt lokal die Ridged-Spitzen: steilere Flanken (x1.25), aber
         * Deckel bei 0.52 -> Hochebenen und breite Ruecken statt durchgehender Nadel-Grate;
         * wo flatness hoch UND ridged niedrig ist, bleiben offene Talboeden im Gebirge. */
        float m = Biomes.mountainWeight(c);
        if (m > 0F) {
            float ridged = (this.mountainNoise.GetNoise(x, z) + 1F) * 0.5F;
            float flatness = smoothstep((this.plateauNoise.GetNoise(x, z) - 0.05F) / 0.45F);
            float shape = lerp(ridged, Math.min(ridged * 1.25F, 0.52F), flatness);
            h += m * shape * MOUNTAIN_AMP;
        }

        /* Wueste ist knochentrocken: Senken unter Meereshoehe werden mit Terrain aufgefuellt
         * statt (durch den Wasser-Fill) zu Tuempeln. Stetig ueber desertness -> keine Kante
         * an der Biomgrenze; max() laesst hoeheres Terrain unveraendert. */
        float dry = desertness(c);
        if (dry > 0F) {
            h = lerp(h, Math.max(h, SEA_LEVEL + 2), dry);
        }
        return h;
    }

    /**
     * Trockenheits-Faktor 0..1 — Gate fuer Wasser-Unterdrueckung (Terrain-Klammer,
     * Fluss-Carving). Die Rampen starten bewusst VOR den Schwellen des DESERT-Buckets
     * (Temperatur > 0.25, Feuchtigkeit < -0.2) und werden per min() statt Produkt
     * kombiniert: so ist schon die gesamte Biomgrenze weitgehend trocken (Fluesse
     * versiegen kurz vor der Wueste), nicht erst das Wuesteninnere.
     */
    private static float desertness(Climate c) {
        return Math.min(smoothstep((c.temperature() - 0.18F) / 0.10F),
                smoothstep((-0.13F - c.humidity()) / 0.10F));
    }

    /**
     * Kontinentalwellen-Offset: sehr niederfrequente Hebung/Senkung (~+75 bis −35) des
     * Grundniveaus ganzer Regionen. Zur Kueste hin auf 0 ausgeblendet, damit die
     * Kuestenlinie am Meeresspiegel verankert bleibt; abgesenktes hohes Binnenland kann
     * bewusst gelegentlich unter den Meeresspiegel fallen (Binnenseen).
     */
    private float upliftOffset(int x, int z, Climate c) {
        float gate = smoothstep((c.continentalness() - Biomes.C_BEACH) / 0.25F);
        if (gate <= 0F) return 0F;
        float n = this.upliftNoise.GetNoise(x, z) + UPLIFT_BIAS;
        if (n >= 0F) return n * UPLIFT_UP * gate;
        /* Senkung nur, wo das Grundniveau Luft nach unten hat: kuestennahe Ebenen (base ~67)
         * bleiben ueber dem Meer, nur hoeheres Binnenland bildet gelegentlich tiefe Senken */
        float headroom = smoothstep((continentSpline(c.continentalness()) - 64F) / 26F);
        return n * UPLIFT_DOWN * headroom * gate;
    }


    /* ------------------------------------------------------------------ Seen (Worley) */

    /**
     * Normierte Ufer-Distanz 0 (Zentrum) .. >=1 (ausserhalb): die echte Distanz wird per
     * Noise nur nach INNEN verzerrt (Buchten) — nie ueber den Radius hinaus, sonst koennte
     * das Wasser jenseits der Ringpunkte auslaufen. Macht aus der Kreisscheibe eine
     * unregelmaessige Uferlinie.
     */
    private float lakeShoreNorm(Lake lake, int x, int z) {
        float d = (float) Math.sqrt(sq(x - lake.centerX) + sq(z - lake.centerZ));
        float bay = Math.max(0F, this.detailNoise.GetNoise(x * 1.9F + 713F, z * 1.9F + 713F));
        return d * (1F + bay * 0.55F) / lake.radius;
    }

    /** Der See, in dessen (Noise-verzerrtem) Ufer (x, z) liegt — oder null. */
    private Lake lakeAt(int x, int z) {
        /* Ein See verlaesst seine Zelle nie (s. Konstanten) -> nur die eigene Zelle pruefen */
        Lake lake = this.lakeFor(Math.floorDiv(x, LAKE_CELL), Math.floorDiv(z, LAKE_CELL));
        if (lake == NO_LAKE) return null;
        if (sq(x - lake.centerX) + sq(z - lake.centerZ) > sq(lake.radius)) return null;
        return (this.lakeShoreNorm(lake, x, z) <= 1F) ? lake : null;
    }

    /** See-Daten der Zelle (gecacht); {@link #NO_LAKE}, wenn die Zelle keinen See traegt. */
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

        /* Nur im (feuchten) Binnenland: keine Seen im Ozean/Strandband oder in Trockenregionen */
        Climate c = this.climate.sampleSmooth(centerX, centerZ);
        if (c.continentalness() < Biomes.C_BEACH + 0.03F) return NO_LAKE;
        if (c.humidity() < -0.1F) return NO_LAKE;

        int radius = LAKE_RADIUS_MIN + (int) (hash01(cellX, cellZ, this.seed, 0x5EE4)
                * (LAKE_RADIUS_MAX - LAKE_RADIUS_MIN));

        /* Spiegel = niedrigster Punkt von Zentrum + Ring minus 1 -> Wasser bleibt im Becken;
         * zu grosses Gefaelle am Ring = Hanglage -> kein See */
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

    /**
     * Wasserspiegel an (x, z): Meeresspiegel, Fluss-Traeger oder Seespiegel — pure Funktion,
     * hauptsaechlich fuer Debug-Karten und Sonden (intern liefert {@link #columnFor} den
     * Spiegel zusammen mit der Hoehe, ohne das Klima doppelt zu samplen).
     */
    public int waterLevelAt(int x, int z) {
        return this.columnFor(x, z, this.climate.sampleSmooth(x, z), true).waterLevel;
    }

    /* ------------------------------------------------------------------ Fluss-Netz (Hooks) */

    /**
     * Leitfeld fuer den Fluss-Trace: glatte Grundhoehe (Spline + Kontinentalwellen +
     * Detail-Basisoktave) plus Gebirgsgewicht als weiche Penalty — Traces laufen um
     * Massive herum statt Canyons zu erzwingen. Bewusst OHNE die hochfrequenten
     * Oktaven/Ridges: die Falllinie soll grossraeumig fallen, nicht lokal zittern.
     */
    float riverGuide(int x, int z) {
        Climate c = this.climate.sampleSmooth(x, z);
        return continentSpline(c.continentalness()) + this.upliftOffset(x, z, c)
                + this.detailBaseNoise.GetNoise(x, z) * 0.533F * lerp(4F, 36F, this.ruggedness(c))
                + Biomes.mountainWeight(c) * MOUNTAIN_AMP * 0.35F;
    }

    /**
     * Traeger fuer das Spiegel-Profil: folgt dem echten Terrain per Detail-Oktaven 1+2
     * (Gewichte 0.533/0.267 = deren Anteile am normierten FBm) bis auf ±~0.6 in Ebenen.
     */
    float riverCarrier(int x, int z) {
        Climate c = this.climate.sampleSmooth(x, z);
        return continentSpline(c.continentalness()) + this.upliftOffset(x, z, c)
                + (this.detailBaseNoise.GetNoise(x, z) * 0.533F
                + this.detailBase2Noise.GetNoise(x, z) * 0.267F)
                * lerp(4F, 36F, this.ruggedness(c));
    }

    /** Kontinentalitaet (glatt) — Quell-Gate des Fluss-Netzes. */
    float continentalnessAt(int x, int z) {
        return this.climate.sampleSmooth(x, z).continentalness();
    }

    /** Liegt (x, z) im Wasser eines Worley-Sees? Quell-Gate des Fluss-Traces. */
    boolean insideLake(int x, int z) {
        return this.lakeAt(x, z) != null;
    }

    /**
     * Der See, dessen Wasser (x, z) naeher als {@code margin} kommt — grosszuegiger
     * Radius-Test OHNE Ufer-Noise, fuer die Muendungs-Erkennung des Fluss-Traces: auch
     * ein Lauf, der ein Seebecken nur STREIFT, muss dort enden, sonst steht sein Spiegel
     * als Wasserwand neben dem tieferen Seespiegel. Prueft die 3x3-Nachbarzellen, weil
     * die Flanke eines Nachbarzellen-Sees in Randnaehe in Reichweite liegen kann.
     */
    Lake lakeNear(int x, int z, int margin) {
        int cellX = Math.floorDiv(x, LAKE_CELL);
        int cellZ = Math.floorDiv(z, LAKE_CELL);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Lake lake = this.lakeFor(cellX + i, cellZ + j);
                if (lake == NO_LAKE) continue;
                long reach = lake.radius + margin;
                if (sq(x - lake.centerX) + sq(z - lake.centerZ) <= reach * reach) return lake;
            }
        }
        return null;
    }

    /** Deterministischer Zell-Hash -> [0, 1) — auch vom Fluss-Netz genutzt. */
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

    /**
     * Zerklueftungs-Faktor 0..1 aus niedriger Erosion, nahe der Kuestenlinie stark gedaempft —
     * sonst zersplittert das Detail-/Shape-Noise die Kueste und das Strandband liegt nicht mehr
     * auf Meereshoehe. Skaliert Detail-Amplitude UND 3D-Verformung.
     */
    private float ruggedness(Climate c) {
        float rugged = smoothstep((0.35F - c.erosion()) / 0.7F);
        float coastDistance = Math.abs(c.continentalness() - (Biomes.C_OCEAN + Biomes.C_BEACH) * 0.5F);
        return rugged * lerp(0.15F, 1F, smoothstep(coastDistance / 0.25F));
    }

    /**
     * Stuetzpunkte der Grundhoehe ueber die Kontinentalitaet (piecewise-linear):
     * Tiefsee 10 -> Schelf -> Kuestenlinie ~62 (bei C_OCEAN) -> flaches Landesinneres bis ~92.
     */
    private static float continentSpline(float c) {
        if (c < -0.55F) return lerpMap(c, -1.00F, -0.55F, 10F, 28F);
        if (c < -0.45F) return lerpMap(c, -0.55F, -0.45F, 28F, 45F);
        if (c < Biomes.C_OCEAN) return lerpMap(c, -0.45F, Biomes.C_OCEAN, 45F, 62F);
        if (c < Biomes.C_BEACH) return lerpMap(c, Biomes.C_OCEAN, Biomes.C_BEACH, 62F, 67F);
        if (c < 0.30F) return lerpMap(c, Biomes.C_BEACH, 0.30F, 67F, 74F);
        return lerpMap(c, 0.30F, 1.00F, 74F, 92F);
    }

    /** Biom an Weltkoordinaten — pures Sampling (mit Grenz-Dither), threadsicher. */
    @Override
    public Biome biomeAt(int x, int z) {
        return Biomes.lookup(this.climate.sample(x, z));
    }

    /**
     * Echte Terrainoberkante (oberster Solid-Block): beruecksichtigt die 3D-Verformung, die
     * die Oberflaeche in zerklueftetem Terrain bis zu ±{@link #SHAPE_AMP_MAX} Bloecke von
     * {@link #sampleHeight} entfernt — Basis fuer Feature-Platzierung (sonst schweben Baeume).
     * Pure Funktion: die Dichte-Gitterpunkte liegen auf globalen 4er/8er-Rastern, das Ergebnis
     * ist daher exakt identisch mit dem generate()-Fill (Hoehlen-Carving ausgenommen, das die
     * Oberflaeche konstruktionsbedingt fast nie beruehrt).
     */
    @Override
    public int surfaceSolidHeight(int x, int z) {
        ColumnSample cs = this.columnFor(x, z, this.climate.sampleSmooth(x, z), true);
        int h2d = cs.height;
        float amp = cs.shapeAmp;
        if (amp <= 0F) return h2d; // flache Biome/Wasserbecken liegen exakt auf der Heightmap

        int top = Math.min(Chunk.HEIGHT - 2, h2d + (int) amp + 3);
        int solidBelow = Math.max(1, h2d - (int) SHAPE_AMP_MAX - 4); // ab hier rein 2D = fest

        /* Shape-Noise bilinear pro Grid-Layer (identisch zu bilinearColumn in generate) */
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

    /** Shape-Noise an einem Grid-Layer, bilinear auf (x,z) — gleiche Mathematik wie generate(). */
    private float shapeLayer(int x0, int z0, float fx, float fz, int cornerY) {
        float yScaled = cornerY * 1.5F;
        float v00 = this.shapeNoise.GetNoise(x0, yScaled, z0);
        float v01 = this.shapeNoise.GetNoise(x0, yScaled, z0 + GRID_XZ);
        float v10 = this.shapeNoise.GetNoise(x0 + GRID_XZ, yScaled, z0);
        float v11 = this.shapeNoise.GetNoise(x0 + GRID_XZ, yScaled, z0 + GRID_XZ);
        return lerp(lerp(v00, v01, fz), lerp(v10, v11, fz), fx);
    }

    /**
     * Oberflaechen-Sample fuers LOD: im Ozean ist die sichtbare Oberflaeche der Wasserspiegel
     * (nicht der Meeresboden), sonst das geteilte Deckmaterial.
     */
    @Override
    public long sampleSurface(int x, int z) {
        Climate smooth = this.climate.sampleSmooth(x, z);
        ColumnSample cs = this.columnFor(x, z, smooth, true);
        if (cs.height < cs.waterLevel) return LodDataSource.pack(Blocks.WATER, cs.waterLevel);
        int top = this.surfaceTop(x, z, cs.height, Biomes.lookup(this.climate.sample(x, z, smooth)),
                cs.uplift, cs.waterLevel);
        return LodDataSource.pack(top, cs.height);
    }

    /**
     * Boden-Sample fuers LOD: wie {@link #sampleSurface}, aber ohne Wasser-Zweig — liefert
     * auch unter Wasser (Ozean/See/Fluss) den festen Boden (Deckmaterial + gecarvte
     * Bodenhoehe). Pure Funktion, rein lesend — generiert nichts, aendert keine Seeds.
     */
    @Override
    public long sampleGroundSurface(int x, int z) {
        Climate smooth = this.climate.sampleSmooth(x, z);
        ColumnSample cs = this.columnFor(x, z, smooth, true);
        int top = this.surfaceTop(x, z, cs.height, Biomes.lookup(this.climate.sample(x, z, smooth)),
                cs.uplift, cs.waterLevel);
        return LodDataSource.pack(top, cs.height);
    }

    @Override
    public LodSurfaces sampleLodSurfaces(int x, int z) {
        Climate smooth = this.climate.sampleSmooth(x, z);
        ColumnSample cs = this.columnFor(x, z, smooth, true);
        int groundBlock = this.surfaceTop(x, z, cs.height,
                Biomes.lookup(this.climate.sample(x, z, smooth)), cs.uplift, cs.waterLevel);
        long ground = LodDataSource.pack(groundBlock, cs.height);
        long surface = cs.height < cs.waterLevel
                ? LodDataSource.pack(Blocks.WATER, cs.waterLevel) : ground;
        return new LodSurfaces(ground, surface);
    }

    /**
     * Deckmaterial an (wx, wz) — von generate() UND LOD genutzt (geteilte Logik gegen Naehte).
     * {@code uplift} verschiebt die Stein-/Schneegrenze mit dem regionalen Grundniveau,
     * sonst waeren hochgehobene Ebenen komplett schneebedeckt. {@code waterLevel} ist der
     * lokale Wasserspiegel (Meer ODER See) — Seeboeden bekommen dieselben Material-Flecken.
     */
    private int surfaceTop(int wx, int wz, int height, Biome biome, float uplift, int waterLevel) {
        if (height < waterLevel) {
            /* Unterwasser-Boden: tiefenabhaengig gemischte Flecken aus Sand/Ton/Erde/Kies
             * statt Ein-Material-Zonen; zwei dekorrelierte Samples desselben Noise
             * (Skalen-Offset-Muster wie V1) fuer unabhaengige Fleckenmuster. Das regionale
             * Sediment-Noise verschiebt die Schwellen pro Fluss-/Meeresgebiet: ton-reiche
             * neben fast ton-freien Abschnitten statt weltweit identischer Mischung. */
            int depth = waterLevel - height;
            float n1 = this.floorNoise.GetNoise(wx, wz);
            float n2 = this.floorNoise.GetNoise(wx * 1.7F + 537F, wz * 1.7F + 537F);
            float sed = this.sedimentNoise.GetNoise(wx, wz);

            if (depth <= 3) {
                /* Ufer: Sand-Basis mit Erd- und Kiesflecken; Ton nur in Ton-Regionen */
                if (n1 > 0.8F - Math.max(0F, sed) * 0.5F) return Blocks.CLAY;
                if (n2 > 0.5F) return Blocks.DIRT;
                if (n1 < -0.6F + Math.max(0F, -sed) * 0.25F) return Blocks.GRAVEL;
                return Blocks.SAND;
            }
            if (depth <= 9) {
                /* Flachwasser: ausgewogene Mischung; negativer sed = kiesige Region */
                if (n1 > 0.55F - sed * 0.3F) return Blocks.CLAY;
                if (n1 < -0.4F - sed * 0.2F) return Blocks.GRAVEL;
                return (n2 > 0.3F) ? Blocks.DIRT : Blocks.SAND;
            }
            /* Tiefe: Kies dominiert, aber mit Ton-, Erd- und Sandbaenken durchsetzt */
            if (n2 > 0.5F) return Blocks.SAND;
            if (n1 > 0.6F - sed * 0.25F) return Blocks.CLAY;
            if (n2 < -0.45F) return Blocks.DIRT;
            return Blocks.GRAVEL;
        }

        /* Stein-/Schneegrenze relativ zum regionalen Grundniveau (uplift), grossraeumig
         * gewellt (snowWobble) und kleinraeumig verwackelt (LINE_DITHER). Zusaetzlich
         * hangabhaengig: an Steilflanken rutscht die Steinlinie nach unten (frueher Fels)
         * und die Schneelinie nach oben (kein Schnee an Waenden) — Schnee folgt dadurch
         * Kuppen und Rinnen statt einer horizontalen Kappe. */
        float lineShift = uplift + this.snowWobbleNoise.GetNoise(wx, wz) * 18F;
        int stoneLine = STONE_LINE + (int) lineShift;
        if (height >= stoneLine - SLOPE_STONE_MAX - (int) LINE_DITHER) {
            float dither = this.detailNoise.GetNoise(wx * 7.3F, wz * 7.3F) * LINE_DITHER;
            float slope = this.slopeAt(wx, wz);
            if (height >= SNOW_LINE + (int) (lineShift + slope * 5F + dither)) return Blocks.SNOW;
            if (height >= stoneLine + (int) (dither - slope * 3F)) return Blocks.STONE;
        }

        /* Strandband rund um den Meeresspiegel, Kante leicht verwackelt */
        int beachTop = SEA_LEVEL + 2 + (int) (this.detailNoise.GetNoise(wx * 7.3F, wz * 7.3F) * 1.5F);
        if (height <= beachTop) return Blocks.SAND;

        /* Inseln in Ozean-Regionen: Grasdecke statt Ozeanboden-Material */
        if (biome == Biomes.OCEAN) return Blocks.GRASS_BLOCK;
        return biome.surfaceBlock;
    }

    /** Nur fuer Debug-Karten (GeneratorMapExporter): Deckmaterial auch unter Wasser sichtbar. */
    public int debugSurfaceTop(int x, int z) {
        Climate smooth = this.climate.sampleSmooth(x, z);
        ColumnSample cs = this.columnFor(x, z, smooth, true);
        return this.surfaceTop(x, z, cs.height, Biomes.lookup(this.climate.sample(x, z, smooth)),
                cs.uplift, cs.waterLevel);
    }

    /** Fuellmaterial unter dem Deckblock (variable Schichtdicke, s. generate). */
    private static int fillerFor(int top, Biome biome) {
        if (top == Blocks.SAND) return Blocks.SANDSTONE;
        if (top == Blocks.GRAVEL || top == Blocks.CLAY || top == Blocks.DIRT) return top;
        if (top == Blocks.SNOW || top == Blocks.STONE) return Blocks.STONE;
        /* Gras immer auf Erde — auch auf Inseln, deren Biom OCEAN ist (fillerBlock = Kies) */
        if (top == Blocks.GRASS_BLOCK) return Blocks.DIRT;
        return biome.fillerBlock;
    }

    @Override
    public void generate(Chunk chunk) {
        long start = System.nanoTime();
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
        final int size = ChunkSection.SIZE;

        /* 1) Spaltendaten: exakte 2D-Hoehe (Basis der Dichte — haelt flache Biome exakt auf
         *    der Heightmap, LOD-konsistent), Materialien und 3D-Amplitude */
        int[] heights = new int[size * size];
        int[] tops = new int[size * size];
        int[] fillers = new int[size * size];
        int[] waterLevels = new int[size * size];
        float[] shapeAmps = new float[size * size];
        float[] uplifts = new float[size * size];
        Biome[] biomes = new Biome[size * size];
        int maxH = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int wx = baseX + x, wz = baseZ + z;
                int i = x * size + z;

                Climate smooth = this.climate.sampleSmooth(wx, wz);
                ColumnSample cs = this.columnFor(wx, wz, smooth, true);
                int h = cs.height;
                /* Biome aus dem vorhandenen Smooth-Sample + Dither ableiten — spart die zweite
                   volle 4-Feld-Klima-Auswertung pro Spalte (bit-identisch zu biomeAt). */
                Biome biome = Biomes.lookup(this.climate.sample(wx, wz, smooth));
                heights[i] = h;
                biomes[i] = biome;
                waterLevels[i] = cs.waterLevel;
                uplifts[i] = cs.uplift;
                tops[i] = this.surfaceTop(wx, wz, h, biome, uplifts[i], cs.waterLevel);
                fillers[i] = fillerFor(tops[i], biome);
                shapeAmps[i] = cs.shapeAmp;
                if (h > maxH) maxH = h;
            }
        }

        /* 2) 3D-Noise nur an Gitterpunkten (9x9 horizontal, alle 8 Bloecke vertikal) */
        int yTop = Math.min(Chunk.HEIGHT - 2, maxH + (int) SHAPE_AMP_MAX + 4);
        int gridsXZ = size / GRID_XZ + 1;                  // 9 Eckpunkte pro Achse
        int gridsY = yTop / GRID_Y + 2;                    // Layer 0..n, deckt yTop+1 ab
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
                    /* y*1.5 staucht das Shape-Noise vertikal -> eher horizontale Strukturen */
                    shapeGrid[gi] = this.shapeNoise.GetNoise(wx, y * 1.5F, wz);
                    cheeseGrid[gi] = this.cheeseNoise.GetNoise(wx, y, wz);
                    sp1Grid[gi] = this.spaghettiNoise1.GetNoise(wx, y, wz);
                    sp2Grid[gi] = this.spaghettiNoise2.GetNoise(wx, y, wz);
                    stone1Grid[gi] = this.stoneNoise1.GetNoise(wx, y, wz);
                    stone2Grid[gi] = this.stoneNoise2.GetNoise(wx, y, wz);
                }
            }
        }

        /* 3) Spalten fuellen: Dichte entscheidet fest/Luft, Hoehlen carven, Coating auf den
         *    obersten Solid-Run, Wasser nur OBERHALB der Oberflaeche */
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

                /* Grid-Spaltenprofile bilinear vorinterpolieren (ein Wert pro Layer) */
                bilinearColumn(shapeGrid, gridsXZ, gridsY, x, z, colShape);
                bilinearColumn(cheeseGrid, gridsXZ, gridsY, x, z, colCheese);
                bilinearColumn(sp1Grid, gridsXZ, gridsY, x, z, colSp1);
                bilinearColumn(sp2Grid, gridsXZ, gridsY, x, z, colSp2);
                bilinearColumn(stone1Grid, gridsXZ, gridsY, x, z, colStone1);
                bilinearColumn(stone2Grid, gridsXZ, gridsY, x, z, colStone2);

                int waterLevel = waterLevels[i];
                /* Ozean-/Fluss-/Seeboeden nicht anstechen, sonst laeuft das Wasser in die Hoehle */
                boolean protectFloor = h2d < waterLevel + 2;
                int colTop = Math.min(yTop, h2d + (int) amp + 3);

                int topSolid = 0;
                for (int y = colTop; y >= 1; y--) {
                    boolean isSolid;
                    if (y <= h2d - (int) SHAPE_AMP_MAX - 4 || amp <= 0F) {
                        /* Unterhalb der Verformungszone (bzw. in flachen Biomen) rein 2D */
                        isSolid = y <= h2d;
                    } else {
                        isSolid = (h2d - y) + lerpColumn(colShape, y) * amp > 0F;
                    }

                    /* Hoehlen: Kammern + Tunnel, nie die Oberflaechen-/Bodenkruste anstechen */
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

                /* Ufersaum-Korrektur: hat die 3D-Verformung die reale Oberflaeche UEBER den
                 * Wasserspiegel gehoben (2D-Hoehe lag darunter), passt das vorberechnete
                 * Unterwasser-Deckmaterial nicht mehr — fuer die echte, trockene Hoehe neu
                 * bestimmen (sonst liegen Ton-/Kiesflaechen offen am Ufer). */
                int top = tops[i];
                int filler = fillers[i];
                if (topSolid >= waterLevel && h2d < waterLevel) {
                    top = this.surfaceTop(baseX + x, baseZ + z, topSolid, biomes[i], uplifts[i], waterLevel);
                    filler = fillerFor(top, biomes[i]);
                }

                /* Filler-Tiefe variiert pro Spalte (1..4 Schichten): mal ein einzelner
                 * Dirt-Block ueber massivem Fels, mal die dicke Schicht — wirkt an
                 * Haengen/Klippen natuerlicher als eine konstante Tiefe */
                float fillerNoise = this.detailNoise.GetNoise((baseX + x) * 3.1F, (baseZ + z) * 3.1F);
                int fillerDepth = Math.clamp(1 + (int) ((fillerNoise + 1F) * 1.7F), 1, 4);

                chunk.setBlock(x, 0, z, Blocks.BEDROCK);
                for (int y = 1; y <= topSolid; y++) {
                    if (!solid[y]) continue; // Hoehlenluft (Sections sind per Default Luft)
                    int block;
                    if (y == topSolid) block = top;
                    else if (y >= topSolid - fillerDepth) block = filler;
                    else block = stoneAt(colStone1, colStone2, y);
                    chunk.setBlock(x, y, z, block);
                }

                /* Wasser: lueckenlose Quell-Saeule bis zum lokalen Wasserspiegel (Meer/See) */
                for (int y = topSolid + 1; y <= waterLevel; y++) {
                    chunk.setBlock(x, y, z, Blocks.WATER);
                }

                /* Bodenpflanzen: biome-abhaengig, deterministisch ueber Dichtefeld + Hash */
                if (topSolid >= waterLevel && topSolid + 3 < Chunk.HEIGHT) {
                    this.placePlants(chunk, x, z, baseX + x, baseZ + z, topSolid, top, biomes[i]);
                }
            }
        }

        this.buildTintGrids(chunk, baseX, baseZ);

        this.trackGenerateTime(System.nanoTime() - start);
    }

    /* Biome-Tint-Glaettung: Biomfarben an einem groben 4-Block-Raster nachschlagen,
     * 3x3-Box-mitteln und bilinear auf die 33x33 Block-Ecken interpolieren ->
     * weiche Farbverlaeufe ueber ~16 Bloecke an Biomgrenzen. */
    private static final int TINT_STEP = 4;
    /* Grobraster-Punkte: lokale Position (i - 2) * 4 fuer i = 0..12 (deckt -8..+40) */
    private static final int TINT_COARSE = 13;
    /* Geglaettete Punkte: lokale Position (k - 1) * 4 fuer k = 0..10 (deckt -4..+36) */
    private static final int TINT_SMOOTH = 11;

    /** Persistenz-Load: Grids ueber DENSELBEN Codepfad wie generate() neu berechnen (bit-identisch). */
    @Override
    public void fillTintCorners(Chunk chunk) {
        this.buildTintGrids(chunk, chunk.chunkX << ChunkSection.SHIFT, chunk.chunkZ << ChunkSection.SHIFT);
    }

    /** Berechnet die 33x33-Eck-Farbgrids des Chunks (pure Funktionswerte -> keine Naehte). */
    private void buildTintGrids(Chunk chunk, int baseX, int baseZ) {
        /* 1) Biomfarben am groben Raster (ungedithertes Klima -> glatte Grenzlinien) */
        int[] coarseGrass = new int[TINT_COARSE * TINT_COARSE];
        int[] coarseFoliage = new int[TINT_COARSE * TINT_COARSE];
        for (int i = 0; i < TINT_COARSE; i++) {
            for (int j = 0; j < TINT_COARSE; j++) {
                int wx = baseX + (i - 2) * TINT_STEP;
                int wz = baseZ + (j - 2) * TINT_STEP;
                Biome biome = Biomes.lookup(this.climate.sampleSmooth(wx, wz));
                coarseGrass[i * TINT_COARSE + j] = biome.grassTint;
                coarseFoliage[i * TINT_COARSE + j] = biome.foliageTint;
            }
        }

        /* 2) 3x3-Box-Glaettung */
        int[] smoothGrass = new int[TINT_SMOOTH * TINT_SMOOTH];
        int[] smoothFoliage = new int[TINT_SMOOTH * TINT_SMOOTH];
        for (int i = 0; i < TINT_SMOOTH; i++) {
            for (int j = 0; j < TINT_SMOOTH; j++) {
                smoothGrass[i * TINT_SMOOTH + j] = boxAverage(coarseGrass, i + 1, j + 1);
                smoothFoliage[i * TINT_SMOOTH + j] = boxAverage(coarseFoliage, i + 1, j + 1);
            }
        }

        /* 3) bilinear auf die Block-Ecken (lokal 0..32) */
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

    /** LOD-Grasfarbe: pures Biom-Sample am Punkt — die 16-Block-Glaettung ist auf LOD-Distanz unsichtbar. */
    @Override
    public int grassTintAt(int x, int z) {
        return Biomes.lookup(this.climate.sampleSmooth(x, z)).grassTint;
    }

    @Override
    public int foliageTintAt(int x, int z) {
        return Biomes.lookup(this.climate.sampleSmooth(x, z)).foliageTint;
    }

    /** Mittel der 3x3-Nachbarschaft im Grobraster (0xRRGGBB, kanalweise). */
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

    /** Bilineare Farb-Interpolation zwischen den Rasterpunkten (k,l)..(k+1,l+1). */
    private static int bilerpColor(int[] grid, int stride, int k, int l, float fx, float fz) {
        int c00 = grid[k * stride + l], c01 = grid[k * stride + l + 1];
        int c10 = grid[(k + 1) * stride + l], c11 = grid[(k + 1) * stride + l + 1];
        int r = (int) lerp(lerp((c00 >> 16) & 0xFF, (c01 >> 16) & 0xFF, fz), lerp((c10 >> 16) & 0xFF, (c11 >> 16) & 0xFF, fz), fx);
        int g = (int) lerp(lerp((c00 >> 8) & 0xFF, (c01 >> 8) & 0xFF, fz), lerp((c10 >> 8) & 0xFF, (c11 >> 8) & 0xFF, fz), fx);
        int b = (int) lerp(lerp(c00 & 0xFF, c01 & 0xFF, fz), lerp(c10 & 0xFF, c11 & 0xFF, fz), fx);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Bodenpflanzen auf dem Deckblock: das weiche Dichtefeld (vegNoise) moduliert die
     * Pro-Block-Wahrscheinlichkeit ({@code biome.plantDensity}), ein deterministischer
     * Block-Hash wuerfelt Platzierung und Art (Gewichte) — natuerliche, durchmischte
     * Verteilung statt binaerer Klumpen. Kein Feature-Pass noetig (einbloeckig).
     */
    private void placePlants(Chunk chunk, int x, int z, int wx, int wz, int topSolid, int top, Biome biome) {
        /* Dichtefaktor 0.25..1: nie ganz kahl, nie Vollteppich */
        float density = 0.25F + 0.75F * (this.vegNoise.GetNoise(wx, wz) + 1F) * 0.5F;

        if (biome == Biomes.DESERT && top == Blocks.SAND) {
            /* Kakteen: sehr vereinzelt statt in Haufen, Hoehe 1-3 aus dem Hash */
            if (hash01(wx, wz, this.seed, 0xCAC7) < 0.004F * density) {
                int height = 1 + (int) (hash01(wx, wz, this.seed, 0xCAC8) * 3F);
                for (int i = 1; i <= height; i++) {
                    chunk.setBlock(x, topSolid + i, z, Blocks.CACTUS);
                }
                return;
            }
        }

        if (top != biome.surfaceBlock || biome.plants.length == 0) return; // Strand/Fels bleibt kahl
        if (hash01(wx, wz, this.seed, 0x9EA7) >= biome.plantDensity * density) return;

        /* Pflanzenart per zweitem Hash gegen die Gewichtssumme */
        int total = 0;
        for (Biome.PlantEntry plant : biome.plants) total += plant.weight();
        int pick = (int) (hash01(wx, wz, this.seed, 0x9EA8) * total);
        for (Biome.PlantEntry plant : biome.plants) {
            pick -= plant.weight();
            if (pick < 0) {
                chunk.setBlock(x, topSolid + 1, z, plant.blockId());
                /* Zweiblock-Pflanzen (tall_grass): obere Haelfte direkt mitsetzen — der
                 * Default-State ist nur die untere (HALF=BOTTOM, vgl. TallPlantBehavior) */
                BlockState state = BlockRegistry.getState(plant.blockId());
                if (state.getValues().containsKey(Properties.HALF)) {
                    chunk.setBlock(x, topSolid + 2, z, state.with(Properties.HALF, BlockHalf.TOP).getId());
                }
                return;
            }
        }
    }

    /** Gesteinsvariante im Untergrund: Noise-Extreme streuen Granit/Diorit/Andesit in den Stein. */
    private static int stoneAt(float[] colStone1, float[] colStone2, int y) {
        float n1 = lerpColumn(colStone1, y);
        if (n1 > STONE_VEIN_THRESHOLD) return Blocks.GRANITE;
        if (n1 < -STONE_VEIN_THRESHOLD) return Blocks.DIORITE;
        if (lerpColumn(colStone2, y) > STONE_VEIN_THRESHOLD) return Blocks.ANDESITE;
        return Blocks.STONE;
    }

    /** Bilineare Interpolation eines Noise-Grids auf die Block-Spalte (x, z) — ein Wert pro Grid-Layer. */
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

    /** Linearer y-Anteil der trilinearen Interpolation auf dem vorinterpolierten Spaltenprofil. */
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
}
