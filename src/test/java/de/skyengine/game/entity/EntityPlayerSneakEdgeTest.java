package de.skyengine.game.entity;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPlayerSneakEdgeTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void releasingSneakDoesNotApplyMomentumRejectedByLedgeGuard() {
        TestWorld world = new TestWorld();
        EntityPlayer player = new EntityPlayer();
        player.setPosition(0.7, 64, 0.5);
        player.onGround = true;
        player.motionX = 0.8;

        player.update(new PlayerControls(0, 0, false, true, false, false, false), world);

        assertEquals(0, player.motionX, 1.0e-9);
        double guardedX = player.x;
        for (int tick = 0; tick < 5; tick++) player.update(PlayerControls.NONE, world);
        assertEquals(guardedX, player.x, 1.0e-9);
        assertTrue(player.y >= 64 - 1.0e-9, "player fell after releasing sneak");
    }

    private static final class TestWorld extends Dimension {
        private static final AABB SUPPORT = new AABB(0, 63, 0, 1, 64, 1);
        private final int stone = BlockRegistry.get(Identifier.of("voxelstories:stone"))
                .getDefaultState().getId();

        TestWorld() { super("__sneak_edge_test", level(), null, null); }

        @Override public int getBlock(int x, int y, int z) {
            return BlockPos.asLong(x, y, z) == BlockPos.asLong(0, 63, 0) ? this.stone : Blocks.AIR;
        }

        @Override public List<AABB> getCollisionBoxes(AABB area) {
            return SUPPORT.intersects(area) ? List.of(SUPPORT) : List.of();
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "sneak-edge-test";
            level.seed = 1;
            return level;
        }
    }
}
