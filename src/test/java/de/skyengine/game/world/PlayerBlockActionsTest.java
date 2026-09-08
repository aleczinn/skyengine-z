package de.skyengine.game.world;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerBlockActionsTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void placementInsideActingPlayerIsRejectedBeforeMutation() {
        TestWorld world = new TestWorld();
        EntityPlayer player = new EntityPlayer();
        player.setPosition(0.5, 64, 0.5);

        assertNull(PlayerBlockActions.planPlacement(world, player, 0, 63, 0, Direction.UP,
                0.5, 0, 0.5, world.stoneStack()));
    }

    @Test
    void placementInsideAnotherPlayerIsRejectedBySharedOccupancyQuery() throws Exception {
        TestWorld world = new TestWorld();
        EntityPlayer actor = new EntityPlayer();
        actor.setPosition(5.5, 64, 0.5);
        EntityPlayer other = new EntityPlayer();
        other.setPosition(0.5, 64, 0.5);
        Field activePlayers = Dimension.class.getDeclaredField("activePlayers");
        activePlayers.setAccessible(true);
        activePlayers.set(world, List.of(actor, other));

        assertNull(PlayerBlockActions.planPlacement(world, actor, 0, 63, 0, Direction.UP,
                0.5, 0, 0.5, world.stoneStack()));
    }

    private static final class TestWorld extends Dimension {
        private final Block stone = BlockRegistry.get(Identifier.of("voxelstories:stone"));

        TestWorld() { super("__placement_test", level(), null, null); }

        ItemStack stoneStack() { return new ItemStack(new BlockItem(this.stone), 1); }

        @Override public int getBlock(int x, int y, int z) {
            return BlockPos.asLong(x, y, z) == BlockPos.asLong(0, 63, 0)
                    ? this.stone.getDefaultState().getId() : Blocks.AIR;
        }

        @Override public List<AABB> getCollisionBoxes(AABB area) { return List.of(); }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "placement-test";
            level.seed = 1;
            return level;
        }
    }
}
