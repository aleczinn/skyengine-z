package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.utils.math.FastNoiseLite;

public class WorldGenerator {

    private final FastNoiseLite noise;

    public WorldGenerator(int seed) {
        this.noise = new FastNoiseLite(seed);
        this.noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.noise.SetFractalOctaves(5);
        this.noise.SetFrequency(0.004F);
    }

    public void generate(Chunk chunk) {
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;

        for (int x = 0; x < ChunkSection.SIZE; x++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                float n = this.noise.GetNoise(baseX + x, baseZ + z);
                int height = 80 + (int) (n * 40);

                for (int y = 0; y <= height; y++) {
                    short block;
                    if (y == height) block = Blocks.GRASS_BLOCK;
                    else if (y >= height - 3) block = Blocks.DIRT;
                    else block = Blocks.STONE;
                    chunk.setBlock(x, y, z, block);
                }

                /* Vegetation: deterministisch über einen zweiten Noise-Sample */
                float veg = this.noise.GetNoise((baseX + x) * 13.7F, (baseZ + z) * 13.7F);
                if (height + 1 < Chunk.HEIGHT) {
                    if (veg > 0.76F) {
                        chunk.setBlock(x, height + 1, z, Blocks.FERN);
                    } else if (veg > 0.68F) {
                        chunk.setBlock(x, height + 1, z, Blocks.ORANGE_TULIP);
                    } else if(veg > 0.55F) {
                        chunk.setBlock(x, height + 1, z, Blocks.SHORT_GRASS);
                    }
                }
            }
        }
    }
}