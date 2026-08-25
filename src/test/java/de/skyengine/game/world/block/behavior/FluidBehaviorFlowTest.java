package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluidBehaviorFlowTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void fallingColumnInsideSourceOceanCreatesNoHorizontalSuction() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, Blocks.WATER);
        world.put(-1, 64, 0, Blocks.WATER);
        world.put(0, 64, -1, Blocks.WATER);
        world.put(0, 64, 1, Blocks.WATER);
        BlockState falling = Blocks.getState(Blocks.WATER)
                .with(Properties.LEVEL, 1).with(Properties.FALLING, true);
        world.put(1, 64, 0, falling.getId());

        double[] flow = new double[2];
        FluidBehavior.flowVector(world, 0, 64, 0, flow);

        assertEquals(0.0, flow[0], 0.000001);
        assertEquals(0.0, flow[1], 0.000001);
    }

    @Test
    void actualDropEdgeStillCreatesDirectedFlow() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, Blocks.WATER);
        world.put(1, 63, 0, Blocks.WATER);

        double[] flow = new double[2];
        FluidBehavior.flowVector(world, 0, 64, 0, flow);

        assertTrue(flow[0] > 0.0);
        assertEquals(0.0, flow[1], 0.000001);
    }

    private static final class TestWorld extends Dimension {
        private final Map<Long, Integer> blocks = new HashMap<>();

        TestWorld() {
            super("__fluid_flow_test", level(), null, null);
        }

        void put(int x, int y, int z, int block) {
            this.blocks.put(BlockPos.asLong(x, y, z), block);
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "fluid-flow-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
