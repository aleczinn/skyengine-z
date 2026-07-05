package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.generator.climate.Climate;
import de.skyengine.game.world.generator.climate.ClimateSampler;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.utils.math.FastNoiseLite;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Klimabasierter Generator (Phase 3): Hoehe und Biome werden aus denselben kontinuierlichen
 * Klimafeldern ({@link ClimateSampler}) abgeleitet — Biomuebergaenge sind dadurch automatisch
 * glatt, ohne Parameter-Blending an Biomgrenzen.
 *
 * <p>Seed-Offsets: ClimateSampler reserviert seed+0..+6, eigene Noises starten ab seed+10.
 */
public class AlphaWorldGeneratorV2 extends WorldGenerator {

    /* Meeresspiegel: bis zu dieser Hoehe wird Wasser aufgefuellt */
    private static final int SEA_LEVEL = 64;
    /* Maximaler Berg-Aufschlag (skaliert mit Biomes.mountainWeight) -> Gipfel bis ~260 */
    private static final float MOUNTAIN_AMP = 170F;
    /* River-Werte unterhalb dieser Schwelle bilden das Flusstal (~15-30 Bloecke breit) */
    private static final float RIVER_VALLEY = 0.045F;
    /* Ab dieser Hoehe: Fels statt Biomdecke */
    private static final int STONE_LINE = 125;
    /* Ab dieser Hoehe: Schneekappe */
    private static final int SNOW_LINE = 160;
    /* Verwackelung der Hoehenlinien, damit keine geraden Konturen entstehen (wie V1) */
    private static final float LINE_DITHER = 10F;

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

    private final ClimateSampler climate;
    /* Lokales Terrain-Detail; Amplitude skaliert mit der Erosion (glatt vs. zerklueftet) */
    private final FastNoiseLite detailNoise;
    /* Berg-Form: Ridged-Fraktal fuer Grate und Gipfel */
    private final FastNoiseLite mountainNoise;
    /* Kleinraeumige Materialflecken fuer Ozean-/Flussboeden (Ton/Kies/Sand) */
    private final FastNoiseLite floorNoise;
    /* Bodenpflanzen-Verteilung (Farn, Gras, Blumen, Dead Bush) */
    private final FastNoiseLite vegNoise;
    /* 3D-Verformung der Oberflaeche (Ueberhaenge, Boegen, unregelmaessige Klippen) */
    private final FastNoiseLite shapeNoise;
    /* Hoehlen: grosse Kammern (Cheese) + zwei unabhaengige Tunnel-Noises (Spaghetti) */
    private final FastNoiseLite cheeseNoise;
    private final FastNoiseLite spaghettiNoise1;
    private final FastNoiseLite spaghettiNoise2;

    /* Generierungszeit-Statistik (threadsicher, Log alle 256 Chunks) */
    private final AtomicLong generateNanos = new AtomicLong();
    private final AtomicInteger generateCount = new AtomicInteger();
    private final Logger logger = LogManager.getLogger(AlphaWorldGeneratorV2.class.getName());

    public AlphaWorldGeneratorV2(int seed) {
        super(seed);
        this.climate = new ClimateSampler(seed);

        this.detailNoise = new FastNoiseLite(seed + 10);
        this.detailNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.detailNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.detailNoise.SetFractalOctaves(4);
        this.detailNoise.SetFrequency(0.004F);

        this.mountainNoise = new FastNoiseLite(seed + 11);
        this.mountainNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.mountainNoise.SetFractalType(FastNoiseLite.FractalType.Ridged);
        this.mountainNoise.SetFractalOctaves(4);
        this.mountainNoise.SetFrequency(0.003F);

        this.floorNoise = new FastNoiseLite(seed + 12);
        this.floorNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.floorNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.floorNoise.SetFractalOctaves(2);
        this.floorNoise.SetFrequency(0.03F);

        this.vegNoise = new FastNoiseLite(seed + 13);
        this.vegNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.vegNoise.SetFrequency(0.055F);

        this.shapeNoise = new FastNoiseLite(seed + 14);
        this.shapeNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.shapeNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.shapeNoise.SetFractalOctaves(3);
        this.shapeNoise.SetFrequency(0.008F);

        this.cheeseNoise = new FastNoiseLite(seed + 15);
        this.cheeseNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.cheeseNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.cheeseNoise.SetFractalOctaves(3);
        this.cheeseNoise.SetFrequency(0.012F);

        this.spaghettiNoise1 = new FastNoiseLite(seed + 16);
        this.spaghettiNoise1.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.spaghettiNoise1.SetFrequency(0.01F);

        this.spaghettiNoise2 = new FastNoiseLite(seed + 17);
        this.spaghettiNoise2.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.spaghettiNoise2.SetFrequency(0.01F);
    }

    @Override
    public int sampleHeight(int x, int z) {
        return this.heightFor(x, z, this.climate.sampleSmooth(x, z));
    }

    private int heightFor(int x, int z, Climate c) {
        /* Grundform aus der Kontinentalitaet: Tiefsee -> Kueste -> Landesinneres */
        float h = continentSpline(c.continentalness());

        /* Erosion moduliert das lokale Detail: glatte Ebenen vs. schroffe Huegel */
        h += this.detailNoise.GetNoise(x, z) * lerp(4F, 36F, this.ruggedness(c));

        /* Berg-Aufschlag nur, wo das Gebirgs-Gewicht wirkt (deckungsgleich mit EXTREME_HILLS) */
        float m = Biomes.mountainWeight(c);
        if (m > 0F) {
            float ridged = (this.mountainNoise.GetNoise(x, z) + 1F) * 0.5F;
            h += m * ridged * MOUNTAIN_AMP;
        }

        h = this.carveRiver(x, z, h, c);

        return Math.min((int) h, Chunk.HEIGHT - 2);
    }

    /**
     * Senkt die Hoehe entlang der Flusslinien auf Wasserspiegel-Niveau ab. Die Flusslogik lebt
     * komplett in der Hoehenberechnung: Bett unter SEA_LEVEL + Wasser-Fill -> Fluesse erscheinen
     * automatisch konsistent in L0 UND LOD.
     */
    private float carveRiver(int x, int z, float h, Climate c) {
        /* Im Ozean/Strandband keine Fluesse einschneiden (dort ist eh Wasserspiegel-Niveau) */
        if (c.continentalness() < Biomes.C_BEACH) return h;

        float r = this.climate.riverValue(x, z);
        if (r >= RIVER_VALLEY) return h;

        /* 0 am Talrand .. 1 in der Flussmitte; *1.4 macht die Sohle flach statt V-foermig */
        float t = smoothstep(1F - r / RIVER_VALLEY);
        return lerp(h, SEA_LEVEL - 3, Math.min(1F, t * 1.4F));
    }

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
     * Tiefsee 30 -> Kuestenlinie ~62 (bei C_OCEAN) -> flaches Landesinneres bis ~92.
     */
    private static float continentSpline(float c) {
        if (c < -0.45F) return lerpMap(c, -1.00F, -0.45F, 30F, 45F);
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
     * Oberflaechen-Sample fuers LOD: im Ozean ist die sichtbare Oberflaeche der Wasserspiegel
     * (nicht der Meeresboden), sonst das geteilte Deckmaterial.
     */
    @Override
    public long sampleSurface(int x, int z) {
        int height = this.sampleHeight(x, z);
        if (height < SEA_LEVEL) return LodDataSource.pack(Blocks.WATER, SEA_LEVEL);
        return LodDataSource.pack(this.surfaceTop(x, z, height, this.biomeAt(x, z)), height);
    }

    /** Deckmaterial an (wx, wz) — von generate() UND LOD genutzt (geteilte Logik gegen Naehte). */
    private int surfaceTop(int wx, int wz, int height, Biome biome) {
        if (height < SEA_LEVEL) {
            /* Unterwasser-Boden: Sand ufernah, dann Ton in ruhigen Flecken, Kies in der Tiefe */
            int depth = SEA_LEVEL - height;
            if (depth <= 4) return Blocks.SAND;
            float floor = this.floorNoise.GetNoise(wx, wz);
            if (depth <= 9) return (floor > 0.35F) ? Blocks.CLAY : Blocks.SAND;
            return (floor > 0.45F) ? Blocks.CLAY : Blocks.GRAVEL;
        }

        /* Stein-/Schneegrenze mit verwackelten Hoehenlinien (nur Gebirge erreicht diese Hoehen) */
        if (height >= STONE_LINE - (int) LINE_DITHER) {
            float dither = this.detailNoise.GetNoise(wx * 7.3F, wz * 7.3F);
            if (height >= SNOW_LINE + (int) (dither * LINE_DITHER)) return Blocks.SNOW;
            if (height >= STONE_LINE + (int) (dither * LINE_DITHER)) return Blocks.STONE;
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
        int height = this.sampleHeight(x, z);
        return this.surfaceTop(x, z, height, this.biomeAt(x, z));
    }

    /** Fuellmaterial unter dem Deckblock (3 Schichten). */
    private static int fillerFor(int top, Biome biome) {
        if (top == Blocks.SAND) return Blocks.SANDSTONE;
        if (top == Blocks.GRAVEL || top == Blocks.CLAY) return top;
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
        float[] shapeAmps = new float[size * size];
        Biome[] biomes = new Biome[size * size];
        int maxH = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int wx = baseX + x, wz = baseZ + z;
                int i = x * size + z;

                Climate smooth = this.climate.sampleSmooth(wx, wz);
                int h = this.heightFor(wx, wz, smooth);
                Biome biome = this.biomeAt(wx, wz);
                heights[i] = h;
                biomes[i] = biome;
                tops[i] = this.surfaceTop(wx, wz, h, biome);
                fillers[i] = fillerFor(tops[i], biome);
                shapeAmps[i] = SHAPE_AMP_MAX * this.ruggedness(smooth);
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
                }
            }
        }

        /* 3) Spalten fuellen: Dichte entscheidet fest/Luft, Hoehlen carven, Coating auf den
         *    obersten Solid-Run, Wasser nur OBERHALB der Oberflaeche */
        float[] colShape = new float[gridsY];
        float[] colCheese = new float[gridsY];
        float[] colSp1 = new float[gridsY];
        float[] colSp2 = new float[gridsY];
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

                /* Ozean-/Flussboeden nicht anstechen, sonst laeuft das Wasser in die Hoehle */
                boolean protectFloor = h2d < SEA_LEVEL + 2;
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

                chunk.setBlock(x, 0, z, Blocks.BEDROCK);
                for (int y = 1; y <= topSolid; y++) {
                    if (!solid[y]) continue; // Hoehlenluft (Sections sind per Default Luft)
                    int block;
                    if (y == topSolid) block = tops[i];
                    else if (y >= topSolid - 3) block = fillers[i];
                    else block = Blocks.STONE;
                    chunk.setBlock(x, y, z, block);
                }

                /* Wasser: lueckenlose Quell-Saeule bis zum Meeresspiegel */
                for (int y = topSolid + 1; y <= SEA_LEVEL; y++) {
                    chunk.setBlock(x, y, z, Blocks.WATER);
                }

                /* Bodenpflanzen: biome-abhaengig, deterministisch ueber das Veg-Noise */
                if (topSolid >= SEA_LEVEL && topSolid + 3 < Chunk.HEIGHT) {
                    this.placePlants(chunk, x, z, baseX + x, baseZ + z, topSolid, tops[i], biomes[i]);
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
     * Bodenpflanzen auf dem Deckblock: auf Gras die {@code biome.plants}-Liste (erste passende
     * Schwelle), in der Wueste zusaetzlich Kakteen (1-3 hoch) auf Sand. Deterministisch ueber
     * das Veg-Noise (V1-Muster) — kein Feature-Pass noetig (einbloeckig, kein Overreach).
     */
    private void placePlants(Chunk chunk, int x, int z, int wx, int wz, int topSolid, int top, Biome biome) {
        float veg = this.vegNoise.GetNoise(wx, wz);

        if (biome == Biomes.DESERT && top == Blocks.SAND) {
            /* Kakteen: sehr sparsam, Hoehe waechst mit dem Noise-Wert */
            if (veg > 0.93F) {
                int height = 1 + Math.min(2, (int) ((veg - 0.93F) * 40F));
                for (int i = 1; i <= height; i++) {
                    chunk.setBlock(x, topSolid + i, z, Blocks.CACTUS);
                }
                return;
            }
        }

        if (top != biome.surfaceBlock) return; // Strandband/Felskuppen bleiben kahl
        for (Biome.PlantEntry plant : biome.plants) {
            if (veg > plant.threshold()) {
                chunk.setBlock(x, topSolid + 1, z, plant.blockId());
                return;
            }
        }
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
