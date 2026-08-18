package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LodConfigTest {

    @Test
    void usesFourLevelsBetweenRenderDistance16AndLodDistance128() {
        LodConfig config = LodConfig.of(16, 128);

        assertEquals(1, config.levelAt(16 * 32.0));
        assertEquals(1, config.levelAt(32 * 32.0 - 1));
        assertEquals(2, config.levelAt(32 * 32.0));
        assertEquals(2, config.levelAt(64 * 32.0 - 1));
        assertEquals(3, config.levelAt(64 * 32.0));
        assertEquals(3, config.levelAt(96 * 32.0 - 1));
        assertEquals(4, config.levelAt(96 * 32.0));
        assertEquals(4, config.levelAt(128 * 32.0 - 1));
        assertEquals(4, config.maxEffectiveLevel());

        Set<Integer> usedLevels = new HashSet<>();
        for (int chunks = 16; chunks < 128; chunks++) {
            usedLevels.add(config.levelAt(chunks * 32.0));
        }
        assertEquals(Set.of(1, 2, 3, 4), usedLevels);
    }
}
