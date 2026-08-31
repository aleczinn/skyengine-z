package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.utils.math.FastNoiseLite;

public class MountainWorldGeneratorV1 extends WorldGenerator {

    /* Meeresspiegel: bis zu dieser Hoehe wird Wasser aufgefuellt */
    private static final int SEA_LEVEL = 64;
    /* Basis-Hoehe der flachen Ebenen */
    private static final int PLAINS_BASE = 68;
    /* Hoehen-Schwankung der Ebenen (~y 60..76 -> gelegentlich kleine Seen) */
    private static final float PLAINS_AMP = 8F;
    /* Maximaler Berg-Aufschlag -> Gipfel bis ~y 266 */
    private static final float MOUNTAIN_AMP = 190F;
    /* Masken-Wert, ab dem der Gebirgs-Einfluss beginnt */
    private static final float MASK_START = 0.05F;
    /* Masken-Wert, ab dem der Gebirgs-Einfluss voll wirkt */
    private static final float MASK_END = 0.55F;
    /* Ab dieser Hoehe: Fels statt Gras */
    private static final int STONE_LINE = 125;
    /* Ab dieser Hoehe: Schneekappe */
    private static final int SNOW_LINE = 160;
    /* Verwackelung der Hoehenlinien, damit keine geraden Konturen entstehen */
    private static final float LINE_DITHER = 10F;

    private final FastNoiseLite plainsNoise;
    private final FastNoiseLite maskNoise;
    private final FastNoiseLite mountainNoise;

    public MountainWorldGeneratorV1(int seed) {
        super(seed);
        /* Basis-Terrain der Ebenen; liefert auch Vegetations- und Dither-Samples (andere Skalen) */
        this.plainsNoise = new FastNoiseLite(seed);
        this.plainsNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.plainsNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.plainsNoise.SetFractalOctaves(4);
        this.plainsNoise.SetFrequency(0.004F);

        /* Gebirgs-Maske: sehr niederfrequent -> grosse zusammenhaengende Regionen */
        this.maskNoise = new FastNoiseLite(seed + 1);
        this.maskNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.maskNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.maskNoise.SetFractalOctaves(3);
        this.maskNoise.SetFrequency(0.0012F);

        /* Berg-Form: Ridged-Fraktal fuer Grate und Gipfel */
        this.mountainNoise = new FastNoiseLite(seed + 2);
        this.mountainNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.mountainNoise.SetFractalType(FastNoiseLite.FractalType.Ridged);
        this.mountainNoise.SetFractalOctaves(4);
        this.mountainNoise.SetFrequency(0.003F);
    }

    @Override
    public int sampleHeight(int x, int z) {
        float plainsH = PLAINS_BASE + this.plainsNoise.GetNoise(x, z) * PLAINS_AMP;

        float mask = smoothstep((this.maskNoise.GetNoise(x, z) - MASK_START) / (MASK_END - MASK_START));

        int height = (int) plainsH;
        if (mask > 0F) {
            /* Ridged nur samplen, wo die Maske ueberhaupt wirkt */
            float ridged = (this.mountainNoise.GetNoise(x, z) + 1F) * 0.5F;
            height += (int) (mask * ridged * MOUNTAIN_AMP);
        }
        return Math.min(height, Chunk.HEIGHT - 2);
    }

    /**
     * Oberflächenmaterial: Im Ozean ist die sichtbare Oberfläche der Wasserspiegel
     * (nicht der Meeresboden), sonst das höhenabhängige Deckmaterial.
     */
    @Override
    public int surfaceBlock(int x, int z) {
        int height = this.sampleHeight(x, z);
        if (height < SEA_LEVEL) return Blocks.WATER;
        return this.surfaceTop(x, z, height);
    }

    /** Deckmaterial nach Höhe inklusive verwackelter Fels-/Schneegrenze. */
    private int surfaceTop(int wx, int wz, int height) {
        int snowLine = SNOW_LINE;
        int stoneLine = STONE_LINE;
        if (height >= STONE_LINE - (int) LINE_DITHER) {
            float dither = this.plainsNoise.GetNoise(wx * 7.3F, wz * 7.3F);
            snowLine += (int) (dither * LINE_DITHER);
            stoneLine += (int) (dither * LINE_DITHER);
        }

        if (height < SEA_LEVEL) {
            /* Unterwasser-Boden: Sand in Ufernaehe, Kies in der Tiefe */
            return (height >= SEA_LEVEL - 4) ? Blocks.SAND : Blocks.GRAVEL;
        } else if (height <= SEA_LEVEL + 2) {
            /* Strand-Band rund um den Meeresspiegel */
            return Blocks.SAND;
        } else if (height >= snowLine) {
            return Blocks.SNOW;
        } else if (height >= stoneLine) {
            return Blocks.STONE;
        }
        return Blocks.GRASS_BLOCK;
    }

    /** Füllmaterial unter dem Deckblock (3 Schichten, s. generate). */
    private static int fillerFor(int top) {
        if (top == Blocks.GRASS_BLOCK) return Blocks.DIRT;
        if (top == Blocks.SNOW) return Blocks.STONE;
        return top;
    }

    @Override
    public void generate(Chunk chunk) {
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;

        for (int x = 0; x < ChunkSection.SIZE; x++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                int wx = baseX + x;
                int wz = baseZ + z;

                int height = this.sampleHeight(wx, wz);

                /* Oberflaechenmaterial nach Hoehe. */
                int top = this.surfaceTop(wx, wz, height);
                int filler = fillerFor(top);

                for (int y = 0; y <= height; y++) {
                    int block;
                    if (y == 0) block = Blocks.BEDROCK;
                    else if (y == height) block = top;
                    else if (y >= height - 3) block = filler;
                    else block = Blocks.STONE;
                    chunk.setBlock(x, y, z, block);
                }

                /* Wasser: lueckenlose Quell-Saeule bis zum Meeresspiegel (Quellen sind passiv stabil) */
                for (int y = height + 1; y <= SEA_LEVEL; y++) {
                    chunk.setBlock(x, y, z, Blocks.WATER);
                }

                /* Vegetation: deterministisch ueber einen zweiten Noise-Sample, nur auf Grasflaechen */
                if (top == Blocks.GRASS_BLOCK && height + 1 < Chunk.HEIGHT) {
                    float veg = this.plainsNoise.GetNoise(wx * 13.7F, wz * 13.7F);
                    if (veg > 0.76F) {
                        chunk.setBlock(x, height + 1, z, Blocks.FERN);
                    } else if (veg > 0.68F) {
                        chunk.setBlock(x, height + 1, z, Blocks.ORANGE_TULIP);
                    } else if (veg > 0.55F) {
                        chunk.setBlock(x, height + 1, z, Blocks.SHORT_GRASS);
                    }
                }
            }
        }
    }

    private static float smoothstep(float t) {
        if (t <= 0F) return 0F;
        if (t >= 1F) return 1F;
        return t * t * (3F - 2F * t);
    }
}
