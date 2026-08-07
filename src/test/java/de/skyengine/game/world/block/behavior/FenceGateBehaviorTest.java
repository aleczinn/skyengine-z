package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FenceGateBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void placementFacesPlayerAndStartsOpenWhenPowered() {
        TestWorld world = new TestWorld();
        world.put(-1, 64, state("redstone_block"));
        BlockState gate = state("oak_fence_gate").getBlock().getPlacementState(
                world, 0, 64, 0, 0, 1, 0, 0.5, 0, 0.5, 90, 0, false);

        assertEquals(Direction.EAST, gate.get(Properties.FACING));
        assertTrue(gate.get(Properties.OPEN));
        assertTrue(gate.get(Properties.POWERED));
        assertFalse(gate.get(Properties.IN_WALL));
    }

    @Test
    void openingFromBackTurnsGateTowardPlayer() {
        TestWorld world = new TestWorld();
        BlockState gate = gate(Direction.NORTH, false, false);
        world.put(0, 64, gate);

        assertTrue(gate.getBlock().onUse(world, 0, 64, 0, gate, 180));

        BlockState changed = stateAt(world, 0, 64);
        assertTrue(changed.get(Properties.OPEN));
        assertEquals(Direction.SOUTH, changed.get(Properties.FACING));
    }

    @Test
    void closingKeepsFacingAndRedstoneEdgesFollowPower() {
        TestWorld world = new TestWorld();
        BlockState open = gate(Direction.WEST, true, false);
        world.put(0, 64, open);
        open.getBlock().onUse(world, 0, 64, 0, open, 0);
        BlockState closed = stateAt(world, 0, 64);
        assertFalse(closed.get(Properties.OPEN));
        assertEquals(Direction.WEST, closed.get(Properties.FACING));

        world.put(-1, 64, state("redstone_block"));
        BlockState powered = closed.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, closed);
        assertTrue(powered.get(Properties.OPEN));
        assertTrue(powered.get(Properties.POWERED));

        world.put(-1, 64, state("air"));
        BlockState unpowered = powered.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, powered);
        assertFalse(unpowered.get(Properties.OPEN));
        assertFalse(unpowered.get(Properties.POWERED));
    }

    @Test
    void collisionIsTallWhenClosedAndEmptyWhenOpen() {
        BlockState northClosed = gate(Direction.NORTH, false, false);
        var northBox = northClosed.getCollisionShape().boxes()[0];
        assertEquals(1.5, northBox.maxY);
        assertEquals(5.0 / 16.0, northBox.minZ);
        assertEquals(11.0 / 16.0, northBox.maxZ);

        BlockState eastClosed = gate(Direction.EAST, false, false);
        var eastBox = eastClosed.getCollisionShape().boxes()[0];
        assertEquals(5.0 / 16.0, eastBox.minX);
        assertEquals(11.0 / 16.0, eastBox.maxX);
        assertTrue(gate(Direction.NORTH, true, false).getCollisionShape().isEmpty());
    }

    @Test
    void fenceConnectsOnlyToPerpendicularGatePosts() {
        TestWorld world = new TestWorld();
        world.put(1, 64, gate(Direction.NORTH, false, false));
        BlockState fence = state("oak_fence").getBlock().getPlacementState(
                world, 0, 64, 0, 0, 1, 0, 0.5, 0, 0.5, 0, 0, false);
        assertTrue(fence.get(Properties.EAST));

        world.put(1, 64, gate(Direction.EAST, false, false));
        fence = state("oak_fence").getBlock().getPlacementState(
                world, 0, 64, 0, 0, 1, 0, 0.5, 0, 0.5, 0, 0, false);
        assertFalse(fence.get(Properties.EAST));
    }

    @Test
    void allGatePropertiesSurviveStableStateCodec() {
        BlockState state = gate(Direction.SOUTH, true, true).with(Properties.IN_WALL, true);
        assertEquals(state, BlockStateCodec.decode(BlockStateCodec.encode(state)));
    }

    private static BlockState gate(Direction facing, boolean open, boolean powered) {
        return state("oak_fence_gate")
                .with(Properties.FACING, facing)
                .with(Properties.OPEN, open)
                .with(Properties.POWERED, powered)
                .with(Properties.IN_WALL, false);
    }

    private static BlockState state(String path) {
        return BlockRegistry.get(Identifier.of("skyengine:" + path)).getDefaultState();
    }

    private static BlockState stateAt(TestWorld world, int x, int y) {
        return Blocks.getState(world.getBlock(x, y, 0));
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(16);

        TestWorld() {
            super("__fence_gate_test", level(), null, null);
        }

        void put(int x, int y, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, 0), state.getId());
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            this.blocks.put(BlockPos.asLong(x, y, z), block);
            return true;
        }

        @Override
        public boolean setBlockWithShapeUpdates(int x, int y, int z, int block) {
            return this.setBlock(x, y, z, block, false);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "fence-gate-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
