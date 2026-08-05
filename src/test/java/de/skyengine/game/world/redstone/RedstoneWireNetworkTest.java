package de.skyengine.game.world.redstone;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RedstoneWireNetworkTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void solvesConnectedNetworksBeyondTheFormer1024CellLimit() {
        TestWorld world = new TestWorld();
        BlockState wire = BlockStateCodec.decode(
                "skyengine:redstone_wire[east=none,north=none,power=0,south=none,west=none]");
        if (wire == null) throw new IllegalStateException("Redstone-Staub fehlt in der Test-Registry");

        int size = 40;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) world.put(x, 64, z, wire.getId());
        }

        RedstoneWireNetwork.update(world, 0, 64, 0);

        BlockState farCorner = Blocks.getState(world.getBlock(size - 1, 64, size - 1));
        boolean connected = false;
        for (Direction direction : Direction.horizontalValues()) {
            connected |= farCorner.get(Properties.wireSide(direction)).isConnected();
        }
        assertTrue(connected, "die Zelle 1599 muss trotz der früheren 1024er-Grenze gelöst werden");
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(2048);

        TestWorld() {
            super("__redstone_wire_test", level(), null, null);
        }

        void put(int x, int y, int z, int stateId) {
            this.blocks.put(BlockPos.asLong(x, y, z), stateId);
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            this.put(x, y, z, block);
            return true;
        }

        @Override
        public void updateBlockStateAt(int x, int y, int z) {
            // Empfänger sind für diesen reinen Netz-Skalierungstest nicht relevant.
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "redstone-wire-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
