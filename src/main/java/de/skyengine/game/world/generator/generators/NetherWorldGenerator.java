package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.WorldgenSeeds;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.utils.math.FastNoiseLite;

/** Klassischer, geschlossener Nether aus reinen dreidimensionalen Noise-Funktionen. */
public final class NetherWorldGenerator extends WorldGenerator {

    public static final int VERSION = 1;
    public static final int ACTIVE_HEIGHT = 128;
    public static final int LAVA_LEVEL = 31;

    private final FastNoiseLite cavern;
    private final FastNoiseLite detail;
    private final FastNoiseLite pillar;
    private final FastNoiseLite patches;

    public NetherWorldGenerator(int seed) {
        super(seed);
        this.cavern = noise(seed + WorldgenSeeds.NETHER_CAVERN, 0.018F);
        this.detail = noise(seed + WorldgenSeeds.NETHER_DETAIL, 0.045F);
        this.pillar = noise(seed + WorldgenSeeds.NETHER_PILLAR, 0.010F);
        this.patches = noise(seed + WorldgenSeeds.NETHER_PATCH, 0.030F);
    }

    private static FastNoiseLite noise(int seed, float frequency) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency(frequency);
        return noise;
    }

    @Override
    public int sampleHeight(int x, int z) {
        return 64;
    }

    @Override
    public int surfaceSolidHeight(int x, int z) {
        return 64;
    }

    @Override
    public int lodWorldBottomState() {
        return Blocks.BEDROCK;
    }

    @Override
    public Biome biomeAt(int x, int z) {
        return Biomes.NETHER_WASTES;
    }

    @Override
    public void generate(Chunk chunk) {
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
        for (int x = 0; x < ChunkSection.SIZE; x++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                for (int y = 0; y < ACTIVE_HEIGHT; y++) {
                    int state = this.stateAt(wx, y, wz);
                    if (state != Blocks.AIR) chunk.setBlock(x, y, z, state);
                }
            }
        }
    }

    private int stateAt(int x, int y, int z) {
        if (isBedrock(x, y, z)) return Blocks.BEDROCK;
        if (y <= 4 || y >= 123) return Blocks.NETHERRACK;

        float edge = Math.max(0F, (10F - Math.min(y, 127 - y)) * 0.10F);
        float density = this.cavern.GetNoise(x, y * 0.85F, z) * 0.82F
                + this.detail.GetNoise(x, y, z) * 0.30F
                + this.pillar.GetNoise(x, y * 0.35F, z) * 0.18F + edge;
        if (density < -0.08F) return y <= LAVA_LEVEL ? Blocks.LAVA : Blocks.AIR;

        float patch = this.patches.GetNoise(x, y * 0.55F, z);
        if (y < 38 && patch > 0.61F) return Blocks.MAGMA;
        if (y < 52 && patch < -0.68F) return Blocks.SOUL_SAND;
        if (y < 58 && patch < -0.58F) return Blocks.SOUL_SOIL;
        if (patch > 0.73F) return Blocks.GRAVEL;
        if (Math.abs(this.pillar.GetNoise(x, y, z)) > 0.78F) return Blocks.BASALT;

        /* Kleine Deckencluster sind positionsrein und greifen nur in feste Netherrackzellen ein. */
        long hash = hash(x, y, z, this.seed + WorldgenSeeds.NETHER_GLOWSTONE);
        if (y > 36 && y < 118 && (hash & 0x7FFFFL) == 0L
                && this.stateDensity(x, y - 1, z) < -0.08F) return Blocks.GLOWSTONE;
        return Blocks.NETHERRACK;
    }

    private float stateDensity(int x, int y, int z) {
        return this.cavern.GetNoise(x, y * 0.85F, z) * 0.82F
                + this.detail.GetNoise(x, y, z) * 0.30F
                + this.pillar.GetNoise(x, y * 0.35F, z) * 0.18F;
    }

    private boolean isBedrock(int x, int y, int z) {
        if (y == 0 || y == 127) return true;
        int distance = y < 64 ? y : 127 - y;
        if (distance > 4) return false;
        long value = hash(x, y, z, this.seed + WorldgenSeeds.NETHER_BEDROCK);
        return (value & 3L) < 5 - distance;
    }

    private static long hash(int x, int y, int z, int seed) {
        long value = x * 0x632BE59BD9B4E019L ^ z * 0x9E3779B97F4A7C15L
                ^ y * 0x94D049BB133111EBL ^ seed;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
