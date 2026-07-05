package de.skyengine.game.world.generator.feature.trees;

import de.skyengine.game.world.generator.feature.FeaturePlacer;

import java.util.Random;

/**
 * Formlogik eines Baumtyps. Vertrag wie {@link de.skyengine.game.world.generator.feature.Feature}
 * (Scheiben-Modell): Form MUSS pure Funktion aus (x, y, z, rng) sein — Stamm via
 * {@link FeaturePlacer#set}, Blaetter via {@link FeaturePlacer#setIfAir}. Maximaler Overreach
 * 32 Bloecke ueber die Quell-Chunk-Grenze.
 */
public interface TreeShape {

    /** Platziert einen Baum mit Stammfuss bei (x, y, z) — y ist der erste Block UEBER dem Boden. */
    void place(FeaturePlacer placer, int x, int y, int z, Random rng);
}
