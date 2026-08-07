package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstoneWireNetwork;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RedstoneWireBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void unpoweredRemovalRunsOnlyVanillasInitialImmediatePhase() {
        TestWorld world = new TestWorld();
        BlockState wire = RedstoneWireNetwork.toCross(state("redstone_wire"))
                .with(Properties.POWER, 0);

        wire.getBlock().onRemoved(world, 0, 64, 0, wire, Blocks.getState(Blocks.AIR));

        assertEquals(36, world.generalUpdates.size());
        assertEquals(0, world.deferredUpdates);
    }

    @Test
    void placementNotifiesVerticalCentersImmediately() {
        TestWorld world = new TestWorld();
        BlockState wire = RedstoneWireNetwork.toCross(state("redstone_wire"))
                .with(Properties.POWER, 0);
        world.put(0, 64, 0, wire);

        wire.getBlock().onPlaced(world, 0, 64, 0, wire);

        assertEquals(12, world.generalUpdates.size());
        assertEquals(0, world.deferredUpdates);
    }

    @Test
    void poweredRemovalRunsEvaluatorAndCornerUpdatesWithoutTickDelay() {
        TestWorld world = new TestWorld();
        BlockState wire = RedstoneWireNetwork.toCross(state("redstone_wire"))
                .with(Properties.POWER, 15);
        world.put(0, 63, 0, state("stone"));
        world.put(0, 64, -1, state("redstone_wire").with(Properties.POWER, 0));

        wire.getBlock().onRemoved(world, 0, 64, 0, wire, Blocks.getState(Blocks.AIR));

        /* 36: sechs Ringe um die entfernte Zelle; 42: sieben Evaluator-Zentren;
           42: direkter benachbarter Wire plus seine sechs Nachbarzentren. */
        assertEquals(120, world.generalUpdates.size());
        assertEquals(0, world.deferredUpdates);
    }

    @Test
    void manualShapeToggleUsesVanillasConductorSideUpdate() {
        TestWorld world = new TestWorld();
        BlockState wire = RedstoneWireNetwork.toCross(state("redstone_wire"))
                .with(Properties.POWER, 0);
        world.put(0, 64, 0, wire);
        world.put(0, 64, -1, state("stone"));

        assertTrue(wire.getBlock().onUse(world, 0, 64, 0, wire));

        assertTrue(world.lastSetUpdatesNeighbors,
                "Vanilla schreibt den Formwechsel mit Block-Flag 3");
        assertEquals(1, world.exceptFacingUpdates.size());
        ExceptFacingUpdate update = world.exceptFacingUpdates.getFirst();
        assertEquals(BlockPos.asLong(0, 64, -1), update.position());
        assertEquals(Direction.SOUTH, update.excluded());
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(8);
        private final List<Long> generalUpdates = new ArrayList<>();
        private final List<ExceptFacingUpdate> exceptFacingUpdates = new ArrayList<>();
        private int deferredUpdates;
        private boolean lastSetUpdatesNeighbors;

        private TestWorld() {
            super("__redstone_wire_behavior_test", level(), null, null);
        }

        private void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            this.blocks.put(BlockPos.asLong(x, y, z), block);
            this.lastSetUpdatesNeighbors = updateNeighbors;
            return true;
        }

        @Override
        protected void updateGeneralStateAt(int x, int y, int z) {
            this.generalUpdates.add(BlockPos.asLong(x, y, z));
        }

        @Override
        public void updateGeneralNeighborsAtExceptFromFacing(int x, int y, int z,
                                                              Direction excluded) {
            this.exceptFacingUpdates.add(
                    new ExceptFacingUpdate(BlockPos.asLong(x, y, z), excluded));
        }

        @Override
        public void deferBlockUpdate(int x, int y, int z) {
            this.deferredUpdates++;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "redstone-wire-behavior-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }

    private record ExceptFacingUpdate(long position, Direction excluded) {}
}
