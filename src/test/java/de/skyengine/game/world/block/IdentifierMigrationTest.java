package de.skyengine.game.world.block;

import de.skyengine.core.SkyEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class IdentifierMigrationTest {
    @Test
    void defaultNamespaceComesFromGamePrefixWithoutLegacyCanonicalization() {
        assertEquals("voxelstories", SkyEngine.GAME_PREFIX);
        assertEquals("voxelstories:stone", Identifier.of("stone").toString());
        assertEquals("voxelstories:stone", Identifier.of("voxelstories:stone").toString());
        assertEquals("skyengine:stone", new Identifier("skyengine", "stone").toString());
        assertEquals("example:stone", Identifier.of("example:stone").toString());
    }
}
