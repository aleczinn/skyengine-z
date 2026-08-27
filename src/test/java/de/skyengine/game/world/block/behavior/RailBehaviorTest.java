package de.skyengine.game.world.block.behavior;

import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RailShape;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RailBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void normalRailBuildsCornersAndSlopesFromNeighbors() {
        TestWorld world = new TestWorld();
        world.put(0, 63, 0, state("stone"));
        world.put(1, 64, 0, rail("rail", RailShape.EAST_WEST));
        world.put(0, 64, 1, rail("rail", RailShape.NORTH_SOUTH));

        BlockState corner = placement(world, "rail", 0, 64, 0, 0);
        assertEquals(RailShape.SOUTH_EAST, RailBehavior.shape(corner));

        world = new TestWorld();
        world.put(0, 63, 0, state("stone"));
        world.put(0, 65, -1, rail("rail", RailShape.NORTH_SOUTH));
        BlockState slope = placement(world, "rail", 0, 64, 0, 0);
        assertEquals(RailShape.ASCENDING_NORTH, RailBehavior.shape(slope));
    }

    @Test
    void specialRailsNeverCreateCornerStatesAndReactToRedstone() {
        TestWorld world = new TestWorld();
        world.put(0, 63, 0, state("stone"));
        world.put(1, 64, 0, rail("powered_rail", RailShape.EAST_WEST));
        world.put(0, 64, 1, rail("powered_rail", RailShape.NORTH_SOUTH));
        world.put(-1, 64, 0, state("redstone_block"));

        BlockState placed = placement(world, "powered_rail", 0, 64, 0, 90);
        BlockState updated = placed.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, placed);

        assertTrue(RailBehavior.shape(updated).isStraight());
        assertEquals(RailShape.EAST_WEST, RailBehavior.shape(updated));
        assertTrue(updated.get(Properties.POWERED));
    }

    @Test
    void detectorRailPowersForMinecartAndSchedulesRecheck() {
        TestWorld world = new TestWorld();
        BlockState detector = rail("detector_rail", RailShape.NORTH_SOUTH);
        world.put(0, 64, 0, detector);

        detector.getBlock().onEntityInside(world, 0, 64, 0, detector, new MinecartEntity());

        BlockState powered = Blocks.getState(world.getBlock(0, 64, 0));
        assertTrue(powered.get(Properties.POWERED));
        assertEquals(20, world.lastScheduledDelay);
        assertEquals(15, powered.getBlock().getWeakPower(world, 0, 64, 0,
                powered, de.skyengine.game.world.block.Direction.NORTH));
    }

    @Test
    void railStatesUseStableCodecAndSpecialRailsExposeOnlySixShapes() {
        BlockState curve = rail("rail", RailShape.NORTH_WEST);
        assertEquals(curve, BlockStateCodec.decode(BlockStateCodec.encode(curve)));
        assertEquals(10, state("rail").getBlock().getStates().size());
        assertEquals(12, state("powered_rail").getBlock().getStates().size());
        assertFalse(state("powered_rail").getValues().containsKey(Properties.RAIL_SHAPE));
    }

    private static BlockState placement(TestWorld world, String id, int x, int y, int z, float yaw) {
        return state(id).getBlock().getPlacementState(world, x, y, z,
                0, 1, 0, 0.5, 0, 0.5, yaw, 0, false);
    }

    private static BlockState rail(String id, RailShape shape) {
        BlockState state = state(id);
        if (state.getValues().containsKey(Properties.RAIL_SHAPE)) {
            return state.with(Properties.RAIL_SHAPE, shape);
        }
        return state.with(Properties.STRAIGHT_RAIL_SHAPE, shape);
    }

    private static BlockState state(String path) {
        return BlockRegistry.get(Identifier.of("voxelstories:" + path)).getDefaultState();
    }

    private static final class TestWorld extends Dimension {
        private final LongIntMap blocks = new LongIntMap(32);
        int lastScheduledDelay = -1;

        TestWorld() { super("__rail_test", level(), null, null); }

        void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        @Override public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override public boolean setBlock(int x, int y, int z, int block, boolean updates) {
            this.blocks.put(BlockPos.asLong(x, y, z), block);
            return true;
        }

        @Override public boolean setBlockWithShapeUpdates(int x, int y, int z, int block) {
            return this.setBlock(x, y, z, block, false);
        }

        @Override public void scheduleTickEarlier(int x, int y, int z, int delayTicks) {
            this.lastScheduledDelay = delayTicks;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "rail-test";
            level.seed = 1;

            return level;
        }
    }
}
