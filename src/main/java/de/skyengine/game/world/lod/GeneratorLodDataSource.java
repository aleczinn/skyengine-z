package de.skyengine.game.world.lod;

import de.skyengine.game.world.generator.WorldGenerator;

/**
 * {@link LodDataSource} über die pure Generator-Funktion: ein Punkt-Sample am
 * Zellmittelpunkt repräsentiert die Zelle. Eine spätere Chunk-/Speicher-Quelle kann
 * stattdessen über die volle size×size-Fläche aggregieren.
 */
public final class GeneratorLodDataSource implements LodDataSource {

    private final WorldGenerator generator;

    public GeneratorLodDataSource(WorldGenerator generator) {
        this.generator = generator;
    }

    @Override
    public long sampleSurface(int x, int z, int size) {
        return this.generator.sampleSurface(x + size / 2, z + size / 2);
    }

    @Override
    public int grassTintAt(int x, int z) {
        return this.generator.grassTintAt(x, z);
    }

    @Override
    public int foliageTintAt(int x, int z) {
        return this.generator.foliageTintAt(x, z);
    }
}
