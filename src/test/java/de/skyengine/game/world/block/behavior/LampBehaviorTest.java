package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LampBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void placementStateAlreadyReflectsNeighborSignal() {
        TestWorld powered = new TestWorld();
        powered.put(-1, 64, 0, state("redstone_block"));

        BlockState lit = placementState(powered);
        BlockState dark = placementState(new TestWorld());

        assertTrue(lit.get(Properties.LIT));
        assertFalse(dark.get(Properties.LIT));
    }

    private static BlockState placementState(Dimension world) {
        return state("redstone_lamp").getBlock().getPlacementState(
                world, 0, 64, 0,
                0, 1, 0, 0.5, 0.5, 0.5,
                0.0f, 0.0f, false);
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("voxelstories:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends Dimension {
        private final LongIntMap blocks = new LongIntMap(4);

        TestWorld() {
            super("__lamp_test", level(), null, null);
        }

        void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "lamp-test";
            level.seed = 1;

            return level;
        }
    }
}
