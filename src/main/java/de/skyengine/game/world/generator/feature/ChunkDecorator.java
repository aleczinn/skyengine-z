package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.List;
import java.util.Random;

/**
 * Feature-Pass (Dekoration) nach der Terrain-Generierung, Scheiben-Modell: Der Ziel-Chunk
 * wertet die Features aller 9 Chunks seines 3×3-Umfelds deterministisch aus, schreibt aber
 * nur Blöcke, die in ihm selbst liegen. Ein Baum an der Grenze wird von jedem geschnittenen
 * Chunk identisch neu berechnet — keine Cross-Chunk-Writes, nichts geht beim
 * Unload/Regenerieren verloren.
 *
 * <p>Hält bewusst KEINE Referenz auf ChunkManager/World: Dekorieren kann konstruktionsbedingt
 * keine Nachbar-Generierung anstoßen (keine Kaskaden/Deadlocks im Worker-Pool — der
 * ChunkManager wartet, nie der Job).
 */
public final class ChunkDecorator {

    private final WorldGenerator generator;
    /* Listen-Index = featureId (geht in den Seed ein) — Reihenfolge nie umsortieren! */
    private final List<Feature> features;

    public ChunkDecorator(WorldGenerator generator, List<Feature> features) {
        this.generator = generator;
        this.features = List.copyOf(features);
    }

    /**
     * Dekoriert den Ziel-Chunk. Feste Reihenfolge (row-major über die 3×3-Quellen, dann
     * Feature-Index), damit jede Zelle bei Schreib-Filtern ({@code setIfAir}) einen
     * deterministischen Vorzustand sieht.
     */
    public void decorate(Chunk target) {
        for (int sx = target.chunkX - 1; sx <= target.chunkX + 1; sx++) {
            for (int sz = target.chunkZ - 1; sz <= target.chunkZ + 1; sz++) {
                for (int i = 0; i < this.features.size(); i++) {
                    Random rng = new Random(featureSeed(this.generator.getSeed(), sx, sz, i));
                    this.features.get(i).place(new FeaturePlacer(target, sx, sz, rng, this.generator));
                }
            }
        }
    }

    /**
     * Seed pro (Quell-Chunk, Feature): worldSeed XOR gepackte Chunk-Koordinaten XOR featureId,
     * durch den SplitMix64-Finalizer gemischt — benachbarte Chunks bekommen unkorrelierte Seeds.
     */
    private static long featureSeed(int worldSeed, int sx, int sz, int featureId) {
        long s = (long) worldSeed ^ Chunk.key(sx, sz) ^ ((long) featureId << 17);
        s = (s ^ (s >>> 30)) * 0xBF58476D1CE4E5B9L;
        s = (s ^ (s >>> 27)) * 0x94D049BB133111EBL;
        return s ^ (s >>> 31);
    }
}
