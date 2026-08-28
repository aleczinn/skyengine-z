package de.skyengine.game;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameContainerPortalArrivalPolicyTest {

    private static final Identifier MINING_PORTAL = Identifier.of("voxelstories:mining_portal");

    @Test
    void onlyMiningPortalArrivalInMiningDimensionCreatesPlatform() {
        assertTrue(GameContainer.shouldCreateMiningArrivalPlatform(
                MINING_PORTAL, WorldgenRegistries.MINING, true));
        assertFalse(GameContainer.shouldCreateMiningArrivalPlatform(
                MINING_PORTAL, WorldgenRegistries.OVERWORLD, true));
        assertFalse(GameContainer.shouldCreateMiningArrivalPlatform(
                Identifier.of("voxelstories:nether_portal"), WorldgenRegistries.MINING, true));
        assertFalse(GameContainer.shouldCreateMiningArrivalPlatform(
                null, WorldgenRegistries.MINING, false));
    }
}
