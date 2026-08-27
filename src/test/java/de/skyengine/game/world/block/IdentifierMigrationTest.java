package de.skyengine.game.world.block;

import de.skyengine.core.SkyEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class IdentifierMigrationTest {
    @Test
    void gameAndLegacyNamespacesCanonicalizeWithoutTouchingForeignContent() {
        assertEquals("voxel_stories", SkyEngine.GAME_PREFIX);
        assertEquals("voxel_stories:stone", Identifier.of("stone").toString());
        assertEquals("voxel_stories:stone", Identifier.of("skyengine:stone").toString());
        assertEquals("voxel_stories:stone", new Identifier("skyengine", "stone").toString());
        assertEquals("example:stone", Identifier.of("example:stone").toString());
    }
}
