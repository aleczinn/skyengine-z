package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PistonBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void risingEdgeQueuesVanillaExtendEventWithDirectionParameter() {
        TestWorld world = new TestWorld();
        BlockState piston = state("piston")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, false);
        world.put(0, 64, 0, piston);
        world.put(-1, 64, 0, state("redstone_block"));

        piston.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, piston);

        assertEquals(0, world.eventId);
        assertEquals(5, world.eventParam);
    }

    @Test
    void fallingEdgeQueuesVanillaContractEventWithDirectionParameter() {
        TestWorld world = new TestWorld();
        BlockState piston = state("sticky_piston")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, true);
        world.put(0, 64, 0, piston);

        piston.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, piston);

        assertEquals(1, world.eventId);
        assertEquals(5, world.eventParam);
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(16);
        private int eventId = -1;
        private int eventParam = -1;

        private TestWorld() {
            super("__piston_behavior_test", level(), null, null);
        }

        private void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public void enqueueBlockEvent(int x, int y, int z, int eventId, int eventParam) {
            this.eventId = eventId;
            this.eventParam = eventParam;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "piston-behavior-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
