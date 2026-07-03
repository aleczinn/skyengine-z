package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.lod.LodDataSource;

public abstract class WorldGenerator {

    protected final int seed;

    public WorldGenerator(int seed) {
        this.seed = seed;
    }

    public abstract int sampleHeight(int x, int z);
    public abstract void generate(Chunk chunk);

    /**
     * Oberflächen-Sample fürs LOD: oberster sichtbarer Block + dessen Höhe, gepackt via
     * {@link LodDataSource#pack}. Pure Funktion, threadsicher. Default: Gras auf
     * {@link #sampleHeight}; Generatoren mit Wasser/Material-Zonen überschreiben das.
     */
    public long sampleSurface(int x, int z) {
        return LodDataSource.pack(Blocks.GRASS_BLOCK, this.sampleHeight(x, z));
    }

    public int getSeed() {
        return seed;
    }
}
