package de.skyengine.game.world.lod;

import de.skyengine.game.world.generator.WorldGenerator;

/**
 * {@link LodDataSource} über die pure Generator-Funktion: ein Punkt-Sample am
 * Zellmittelpunkt repräsentiert die Zelle.
 *
 * <p><b>Aktuell nicht verdrahtet</b> — die Engine nutzt {@link WorldLodDataSource}
 * (nah: echte Chunkdaten, fern: Generator). Bewusst behalten als rein generatorbasierte
 * Quelle für GL-freie Werkzeuge/Debug; NICHT versehentlich in World.init einsetzen.
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
    public long sampleGround(int x, int z, int size) {
        return this.generator.sampleGroundSurface(x + size / 2, z + size / 2);
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
