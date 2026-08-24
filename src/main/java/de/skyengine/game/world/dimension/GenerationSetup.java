package de.skyengine.game.world.dimension;

import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.Feature;

import java.util.List;

/** Vollstaendig aufgeloeste Weltgenerierung einer Dimension. */
public record GenerationSetup(WorldGenerator generator, List<Feature> features, StorageMode storageMode) {
    public GenerationSetup {
        features = List.copyOf(features);
    }

    public enum StorageMode {
        GENERATED,
        IMPORTED
    }
}
