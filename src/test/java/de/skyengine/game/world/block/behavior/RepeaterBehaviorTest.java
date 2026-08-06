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
import de.skyengine.game.world.tick.TickPriority;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RepeaterBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void risingEdgeUsesHighPriority() {
        TestWorld world = new TestWorld();
        BlockState repeater = repeater(false);
        world.put(0, 64, 0, repeater);
        world.put(-1, 64, 0, state("redstone_block"));

        repeater.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, repeater);

        assertEquals(TickPriority.HIGH, world.scheduledPriority);
    }

    @Test
    void fallingEdgeUsesVeryHighPriority() {
        TestWorld world = new TestWorld();
        BlockState repeater = repeater(true);
        world.put(0, 64, 0, repeater);

        repeater.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, repeater);

        assertEquals(TickPriority.VERY_HIGH, world.scheduledPriority);
    }

    @Test
    void forwardDiodeUsesExtremelyHighPriority() {
        TestWorld world = new TestWorld();
        BlockState repeater = repeater(false);
        world.put(0, 64, 0, repeater);
        world.put(-1, 64, 0, state("redstone_block"));
        world.put(1, 64, 0, repeater(false));

        repeater.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, repeater);

        assertEquals(TickPriority.EXTREMELY_HIGH, world.scheduledPriority);
    }

    @Test
    void stretchedShortPulseSchedulesVeryHighFallingTick() {
        TestWorld world = new TestWorld();
        BlockState repeater = repeater(false);
        world.put(0, 64, 0, repeater);

        repeater.getBlock().scheduledTick(world, 0, 64, 0, repeater);

        assertEquals(TickPriority.VERY_HIGH, world.scheduledPriority);
    }

    @Test
    void placementStateIsImmediatelyLockedByPoweredSideDiode() {
        TestWorld world = new TestWorld();
        world.put(0, 63, 0, state("stone"));
        world.put(0, 64, -1, repeater(true).with(Properties.FACING, Direction.SOUTH));

        BlockState placed = state("repeater").getBlock().getPlacementState(
                world, 0, 64, 0,
                0, 1, 0,
                0.5, 0.5, 0.5,
                90.0f, 0.0f, false);

        assertTrue(placed.get(Properties.LOCKED));
    }

    @Test
    void placementAndRemovalNotifyBlockInFrontOfOutput() {
        TestWorld world = new TestWorld();
        BlockState repeater = repeater(false);

        repeater.getBlock().onPlaced(world, 3, 64, 4, repeater);
        repeater.getBlock().onRemoved(world, 3, 64, 4, repeater, Blocks.getState(Blocks.AIR));

        assertEquals(2, world.neighborUpdates);
        assertEquals(4, world.lastNeighborX);
        assertEquals(4, world.lastNeighborZ);
    }

    private static BlockState repeater(boolean powered) {
        return state("repeater")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.DELAY, 1)
                .with(Properties.LOCKED, false)
                .with(Properties.POWERED, powered);
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(8);
        private TickPriority scheduledPriority;
        private int neighborUpdates;
        private int lastNeighborX;
        private int lastNeighborZ;

        TestWorld() {
            super("__repeater_test", level(), null, null);
        }

        void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delayTicks, TickPriority priority) {
            this.scheduledPriority = priority;
        }

        @Override
        public void updateNeighbors(int x, int y, int z) {
            this.neighborUpdates++;
            this.lastNeighborX = x;
            this.lastNeighborZ = z;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "repeater-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
