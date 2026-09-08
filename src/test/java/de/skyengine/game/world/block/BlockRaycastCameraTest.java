package de.skyengine.game.world.block;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class BlockRaycastCameraTest {
    @BeforeAll static void bootstrapBlocks() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void cameraIgnoresNonCollidingTallGrassAndStopsAtSolidTerrain() {
        Dimension world = new Dimension("__camera_raycast_test", level(), null, null) {
            @Override public int getBlock(int x, int y, int z) {
                if (y != 64 || z != 0) return Blocks.AIR;
                if (x == 1) return Blocks.TALL_GRASS;
                if (x == 2) return Blocks.STONE;
                return Blocks.AIR;
            }
            @Override public de.skyengine.game.world.block.shape.BlockShape getCollisionShape(
                    int x, int y, int z) {
                return Blocks.getState(getBlock(x, y, z)).getCollisionShape();
            }
        };
        Vector3d origin = new Vector3d(0.5, 64.5, 0.5);
        Vector3d direction = new Vector3d(1, 0, 0);

        assertEquals(Blocks.TALL_GRASS,
                BlockRaycast.raycast(world, origin, direction, 4).block());
        BlockRaycast.Hit cameraHit = BlockRaycast.raycastCollision(world, origin, direction, 4);
        assertNotNull(cameraHit);
        assertEquals(Blocks.STONE, cameraHit.block());
    }

    private static LevelData level() {
        LevelData level = new LevelData();
        level.name = "camera-raycast-test";
        level.seed = 1;
        return level;
    }
}
