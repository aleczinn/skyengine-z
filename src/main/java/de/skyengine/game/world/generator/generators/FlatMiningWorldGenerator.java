package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.utils.math.FastNoiseLite;

/** Billige flache Miningwelt mit geschuetzten 3D-Noise-Hoehlen. */
public final class FlatMiningWorldGenerator extends WorldGenerator {

    public static final int VERSION = 1;
    public static final int SURFACE_Y = 95;

    private final FastNoiseLite caveA;
    private final FastNoiseLite caveB;

    public FlatMiningWorldGenerator(int seed) {
        super(seed);
        this.caveA = cave(seed + 71, 0.022F);
        this.caveB = cave(seed + 72, 0.035F);
    }

    private static FastNoiseLite cave(int seed, float frequency) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency(frequency);
        return noise;
    }

    @Override public int sampleHeight(int x, int z) { return SURFACE_Y; }
    @Override public int surfaceSolidHeight(int x, int z) { return SURFACE_Y; }
    @Override public long sampleSurface(int x, int z) { return LodDataSource.pack(Blocks.GRASS_BLOCK, SURFACE_Y); }
    @Override public int lodWorldBottomState() { return Blocks.BEDROCK; }
    @Override public Biome biomeAt(int x, int z) { return Biomes.PLAINS; }
    @Override public int grassTintAt(int x, int z) { return Tints.GRASS; }
    @Override public int foliageTintAt(int x, int z) { return Tints.FOLIAGE; }

    @Override
    public void generate(Chunk chunk) {
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
        for (int x = 0; x < ChunkSection.SIZE; x++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                int wx = baseX + x, wz = baseZ + z;
                chunk.setBlock(x, 0, z, Blocks.BEDROCK);
                for (int y = 1; y <= 91; y++) {
                    if (y >= 8 && y <= 88 && this.isCave(wx, y, wz)) continue;
                    chunk.setBlock(x, y, z, Blocks.STONE);
                }
                chunk.setBlock(x, 92, z, Blocks.DIRT);
                chunk.setBlock(x, 93, z, Blocks.DIRT);
                chunk.setBlock(x, 94, z, Blocks.DIRT);
                chunk.setBlock(x, SURFACE_Y, z, Blocks.GRASS_BLOCK);
            }
        }
    }

    private boolean isCave(int x, int y, int z) {
        float broad = this.caveA.GetNoise(x, y * 0.75F, z);
        float tunnel = Math.abs(this.caveB.GetNoise(x, y, z));
        return broad > 0.48F || tunnel < 0.055F;
    }
}
