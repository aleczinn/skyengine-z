package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Identifier;

import java.util.function.IntFunction;

/** Registrierbarer Generator samt effektiver Save-Version. */
public record GeneratorDefinition(Identifier id, int version, IntFunction<GenerationSetup> factory) {
    public GeneratorDefinition {
        if (version < 1) throw new IllegalArgumentException("Generator-Version muss positiv sein: " + id);
        if (factory == null) throw new IllegalArgumentException("Generator-Factory fehlt: " + id);
    }

    public GenerationSetup create(int seed) {
        return this.factory.apply(seed);
    }
}
