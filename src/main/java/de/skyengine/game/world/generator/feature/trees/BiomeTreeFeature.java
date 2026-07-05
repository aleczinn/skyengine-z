package de.skyengine.game.world.generator.feature.trees;

import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.feature.Feature;
import de.skyengine.game.world.generator.feature.FeaturePlacer;

import java.util.Random;

/**
 * Biome-abhaengige Baumplatzierung: pro Quell-Chunk eine feste Anzahl Versuche; jeder Versuch
 * wuerfelt Position, prueft die {@code treeChance} des Bioms und den passenden Traeger-Block
 * (Gras bzw. Sand am Karibikstrand) — alles rein aus RNG + purem Sampling (Scheiben-Vertrag,
 * alle RNG-Zuege in fester Reihenfolge unabhaengig vom Ausgang der Checks).
 */
public final class BiomeTreeFeature implements Feature {

    private static final int MAX_ATTEMPTS = 8;

    @Override
    public void place(FeaturePlacer placer) {
        Random rng = placer.random();

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            int x = placer.sourceMinX() + rng.nextInt(ChunkSection.SIZE);
            int z = placer.sourceMinZ() + rng.nextInt(ChunkSection.SIZE);
            float roll = rng.nextFloat();

            Biome biome = placer.biome(x, z);
            if (biome.trees.length == 0 || roll >= biome.treeChance) continue;

            /* Nur auf dem Deckblock des Bioms (schliesst Wasser, Fels- und Schneekuppen aus) */
            if (placer.surfaceBlock(x, z) != biome.surfaceBlock) continue;

            TreeShape shape = TreeShapes.pick(biome.trees, rng);
            shape.place(placer, x, placer.surfaceHeight(x, z) + 1, z, rng);
        }
    }
}
