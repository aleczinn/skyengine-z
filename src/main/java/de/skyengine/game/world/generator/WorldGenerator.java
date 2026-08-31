package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;

public abstract class WorldGenerator {

    protected final int seed;

    public WorldGenerator(int seed) {
        this.seed = seed;
    }

    public abstract int sampleHeight(int x, int z);
    public abstract void generate(Chunk chunk);

    /**
     * Biom an einer Weltposition. Die pure Abfrage wird von Features, Befehlen und Debug-
     * Anzeigen auch außerhalb bereits geladener Chunks verwendet.
     */
    public Biome biomeAt(int x, int z) {
        return Biomes.PLAINS;
    }

    /**
     * Echte Terrainoberkante (oberster Solid-Block) als Basis für Feature-Platzierung.
     * Generatoren mit 3D-Dichte überschreiben die 2D-Heightmap-Vorgabe.
     */
    public int surfaceSolidHeight(int x, int z) {
        return this.sampleHeight(x, z);
    }

    /**
     * Oberster sichtbarer Block einer Weltspalte. Features verwenden diese pure Abfrage
     * auch über Chunkgrenzen hinweg. Generatoren mit Wasser oder Materialzonen überschreiben sie.
     */
    public int surfaceBlock(int x, int z) {
        return Blocks.GRASS_BLOCK;
    }

    /**
     * Befüllt die 33x33-Tint-Eck-Grids eines geladenen Chunks neu. Der Persistenzpfad nutzt
     * denselben Code wie die reguläre Generierung; Generatoren ohne Biome-Tints bleiben No-op.
     */
    public void fillTintCorners(Chunk chunk) {}

    public int getSeed() {
        return seed;
    }
}
