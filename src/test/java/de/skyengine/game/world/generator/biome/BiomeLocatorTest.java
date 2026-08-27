package de.skyengine.game.world.generator.biome;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BiomeLocatorTest {

    @Test
    void findsBiomeOnConfiguredSamplingGrid() {
        WorldGenerator generator = new WorldGenerator(1) {
            @Override public int sampleHeight(int x, int z) { return 64; }
            @Override public void generate(Chunk chunk) { }
            @Override public Biome biomeAt(int x, int z) {
                return x == 64 && z == -32 ? Biomes.DESERT : Biomes.PLAINS;
            }
        };

        BiomeLocator.Result result = BiomeLocator.locate(generator, Biomes.DESERT,
                0, 0, 128, 32);

        assertNotNull(result);
        assertEquals(64, result.x());
        assertEquals(-32, result.z());
        assertEquals(72, result.distance());
    }

    @Test
    void resolvesNamesCaseInsensitivelyAndRejectsInvalidSearches() {
        assertSame(Biomes.SPRUCE_FOREST, BiomeLocator.byName("SPRUCE_FOREST"));
        assertNull(BiomeLocator.byName("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> BiomeLocator.locate(null, Biomes.PLAINS, 0, 0, 10, 1));
    }
}
