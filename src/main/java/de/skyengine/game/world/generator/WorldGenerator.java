package de.skyengine.game.world.generator;

import de.skyengine.game.world.chunk.Chunk;

public abstract class WorldGenerator {

    protected final int seed;

    public WorldGenerator(int seed) {
        this.seed = seed;
    }

    public abstract void generate(Chunk chunk);

    public int getSeed() {
        return seed;
    }
}
