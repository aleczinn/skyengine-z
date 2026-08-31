package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.List;
import java.util.Random;

/**
 * Feature-Pass nach der Terrain-Generierung. Der Ziel-Chunk wertet die Features aller
 * neun Chunks seines 3x3-Umfelds deterministisch aus, schreibt aber nur Blöcke, die in ihm
 * selbst liegen. Grenzübergreifende Features werden dadurch ohne Cross-Chunk-Writes erzeugt.
 */
public final class ChunkDecorator {

    private final WorldGenerator generator;
    /* Listen-Index = featureId und damit Teil des Seeds; Reihenfolge nicht umsortieren. */
    private final List<Feature> features;

    public ChunkDecorator(WorldGenerator generator, List<Feature> features) {
        this.generator = generator;
        this.features = List.copyOf(features);
    }

    public void decorate(Chunk target) {
        for (int sx = target.chunkX - 1; sx <= target.chunkX + 1; sx++) {
            for (int sz = target.chunkZ - 1; sz <= target.chunkZ + 1; sz++) {
                for (int i = 0; i < this.features.size(); i++) {
                    Random rng = new Random(featureSeed(this.generator.getSeed(), sx, sz, i));
                    this.features.get(i).place(new FeaturePlacer(target, sx, sz, rng, this.generator));
                }
            }
        }
        target.materializeStructureBlockEntities();
    }

    public int cacheFingerprint() {
        int hash = 1;
        for (Feature feature : this.features) {
            hash = 31 * hash + feature.getClass().getName().hashCode();
            hash = 31 * hash + feature.cacheVersion();
        }
        return hash;
    }

    private static long featureSeed(int worldSeed, int sx, int sz, int featureId) {
        long s = (long) worldSeed ^ Chunk.key(sx, sz) ^ ((long) featureId << 17);
        s = (s ^ (s >>> 30)) * 0xBF58476D1CE4E5B9L;
        s = (s ^ (s >>> 27)) * 0x94D049BB133111EBL;
        return s ^ (s >>> 31);
    }
}
