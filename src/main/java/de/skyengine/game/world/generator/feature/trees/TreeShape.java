package de.skyengine.game.world.generator.feature.trees;

import de.skyengine.game.world.generator.feature.FeatureContext;

import java.util.Random;

/**
 * Formlogik eines Baumtyps. Vertrag wie {@link de.skyengine.game.world.generator.feature.Feature}
 * (Scheiben-Modell): Form MUSS pure Funktion aus (x, y, z, rng) sein — Stamm via
 * {@link FeatureContext#set}, Blaetter via {@link FeatureContext#setIfAir}. Maximaler Overreach
 * 32 Bloecke ueber die Quell-Chunk-Grenze.
 */
public interface TreeShape {

    /** Platziert einen Baum mit Stammfuss bei (x, y, z) — y ist der erste Block UEBER dem Boden. */
    void place(FeatureContext placer, int x, int y, int z, Random rng);
}
