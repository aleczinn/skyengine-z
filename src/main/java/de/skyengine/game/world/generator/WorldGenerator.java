package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
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

    /**
     * Biom an Weltposition — pures Sampling, threadsicher. Default: Ebene (Generatoren ohne
     * Biome, z.B. V1); Biome-Generatoren ueberschreiben das.
     */
    public Biome biomeAt(int x, int z) {
        return Biomes.PLAINS;
    }

    /**
     * Gras-Farbe an Weltposition (fuers LOD; L0 nutzt die Tint-Grids aus generate()).
     * Default: fester Platzhalter — Generatoren mit Biomen ueberschreiben das.
     */
    public int grassTintAt(int x, int z) {
        return Tints.GRASS;
    }

    /** Laub-Farbe an Weltposition, s. {@link #grassTintAt}. */
    public int foliageTintAt(int x, int z) {
        return Tints.FOLIAGE;
    }

    public int getSeed() {
        return seed;
    }
}
