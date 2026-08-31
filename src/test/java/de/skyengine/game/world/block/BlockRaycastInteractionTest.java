package de.skyengine.game.world.block;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BlockRaycastInteractionTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void interactiveRayStopsAtFirstUnreadyCell() {
        RaycastWorld world = new RaycastWorld(1);
        Vector3d origin = new Vector3d(0.5, 64.5, 0.5);
        Vector3d direction = new Vector3d(1, 0, 0);

        assertNull(BlockRaycast.raycastInteractive(world, origin, direction, 5));

        BlockRaycast.Hit ordinary = BlockRaycast.raycast(world, origin, direction, 5);
        assertNotNull(ordinary);
        assertEquals(2, ordinary.x());
    }

    @Test
    void interactiveRayHitsBlockWhenEveryTraversedCellIsReady() {
        RaycastWorld world = new RaycastWorld(Integer.MAX_VALUE);

        BlockRaycast.Hit hit = BlockRaycast.raycastInteractive(world,
                new Vector3d(0.5, 64.5, 0.5), new Vector3d(1, 0, 0), 5);

        assertNotNull(hit);
        assertEquals(2, hit.x());
        assertEquals(Blocks.STONE, hit.block());
    }

    private static final class RaycastWorld extends Dimension {
        private final int blockedFromX;

        RaycastWorld(int blockedFromX) {
            super("__raycast_interaction_test", level(), null, null);
            this.blockedFromX = blockedFromX;
        }

        @Override
        public boolean isPlayerInteractionReady(int x, int y, int z) {
            return x < this.blockedFromX;
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return x == 2 && y == 64 && z == 0 ? Blocks.STONE : Blocks.AIR;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "raycast-interaction-test";
            level.seed = 1;

            return level;
        }
    }
}
