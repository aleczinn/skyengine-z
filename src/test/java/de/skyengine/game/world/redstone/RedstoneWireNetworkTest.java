package de.skyengine.game.world.redstone;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RedstoneSide;
import de.skyengine.game.world.block.state.SlabType;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RedstoneWireNetworkTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void vanillaEvaluatorPropagatesAcrossMoreThan1024ConnectedCells() {
        TestWorld world = new TestWorld();
        BlockState wire = wireState();

        int size = 40;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                world.put(x, 63, z, state("stone").getId());
                world.put(x, 64, z, wire.getId());
            }
        }

        RedstoneWireNetwork.update(world, 0, 64, 0);

        BlockState farCorner = Blocks.getState(world.getBlock(size - 1, 64, size - 1));
        boolean connected = false;
        for (Direction direction : Direction.horizontalValues()) {
            connected |= farCorner.get(Properties.wireSide(direction)).isConnected();
        }
        assertTrue(connected, "die Zelle 1599 muss trotz der früheren 1024er-Grenze gelöst werden");
    }

    @Test
    void independentWorldsDoNotSuppressEachOtherAcrossThreads() throws Exception {
        CountDownLatch firstSolverEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSolver = new CountDownLatch(1);
        TestWorld first = new TestWorld(firstSolverEntered, releaseFirstSolver);
        TestWorld second = new TestWorld();
        BlockState wire = wireState();
        for (TestWorld world : new TestWorld[] {first, second}) {
            world.put(0, 63, 0, state("stone").getId());
            world.put(1, 63, 0, state("stone").getId());
            world.put(0, 64, 0, wire.getId());
            world.put(1, 64, 0, wire.getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstSolve = executor.submit(() -> RedstoneWireNetwork.update(first, 0, 64, 0));
            assertTrue(firstSolverEntered.await(5, TimeUnit.SECONDS),
                    "der erste Solver muss nach gesetztem Reentrancy-Guard warten");

            Future<?> secondSolve = executor.submit(() -> RedstoneWireNetwork.update(second, 0, 64, 0));
            secondSolve.get(5, TimeUnit.SECONDS);
            releaseFirstSolver.countDown();
            firstSolve.get(5, TimeUnit.SECONDS);

            assertConnected(second, 1, 64, 0,
                    "eine unabhängige Welt darf nicht vom laufenden Solver unterdrückt werden");
        } finally {
            releaseFirstSolver.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void separatesRedstoneConductionFromVisualOpacity() {
        assertTrue(state("stone").isRedstoneConductor());
        assertFalse(state("glass").isRedstoneConductor());
        assertFalse(state("observer").isRedstoneConductor());
        assertFalse(state("hopper").isRedstoneConductor());
        assertFalse(state("stone_slab").with(Properties.SLAB_TYPE,
                de.skyengine.game.world.block.state.SlabType.BOTTOM).isRedstoneConductor());
        assertTrue(state("stone_slab").with(Properties.SLAB_TYPE,
                de.skyengine.game.world.block.state.SlabType.DOUBLE).isRedstoneConductor());
    }

    @Test
    void opaqueObserverAboveLowerWireDoesNotCutUpwardConnection() {
        TestWorld world = steppedWireWorld(state("observer"));

        RedstoneWireNetwork.update(world, 0, 64, 0);

        assertEquals(14, Blocks.getState(world.getBlock(1, 65, 0)).get(Properties.POWER));
    }

    @Test
    void conductiveBlockAboveLowerWireCutsUpwardConnection() {
        TestWorld world = steppedWireWorld(state("stone"));

        RedstoneWireNetwork.update(world, 0, 64, 0);

        assertEquals(0, Blocks.getState(world.getBlock(1, 65, 0)).get(Properties.POWER));
    }

    @Test
    void wireDoesNotClimbUnsupportedBottomSlab() {
        TestWorld world = climbShapeWorld(
                state("stone_slab").with(Properties.SLAB_TYPE, SlabType.BOTTOM));

        RedstoneWireNetwork.update(world, 0, 64, 0);

        assertEquals(RedstoneSide.NONE,
                Blocks.getState(world.getBlock(0, 64, 0)).get(Properties.WIRE_EAST));
    }

    @Test
    void wireClimbsHopperAndTopSlabWithoutDrawingVerticalSide() {
        for (BlockState support : new BlockState[]{
                state("hopper"),
                state("stone_slab").with(Properties.SLAB_TYPE, SlabType.TOP)
        }) {
            TestWorld world = climbShapeWorld(support);

            RedstoneWireNetwork.update(world, 0, 64, 0);

            assertEquals(RedstoneSide.SIDE,
                    Blocks.getState(world.getBlock(0, 64, 0)).get(Properties.WIRE_EAST));
        }
    }

    @Test
    void wireMayBePlacedOnHopperAndGlassButNotBottomSlab() {
        TestWorld world = new TestWorld();
        BlockState wire = wireState();
        world.put(0, 63, 0, state("hopper").getId());

        assertTrue(wire.getBlock().getPlacementState(
                world, 0, 64, 0, 0, 1, 0, 0.5, 1, 0.5, 0, 0, false) != null);

        world.put(0, 63, 0, state("glass").getId());
        assertTrue(wire.getBlock().getPlacementState(
                world, 0, 64, 0, 0, 1, 0, 0.5, 1, 0.5, 0, 0, false) != null);

        world.put(0, 63, 0,
                state("stone_slab").with(Properties.SLAB_TYPE, SlabType.BOTTOM).getId());
        assertTrue(wire.getBlock().getPlacementState(
                world, 0, 64, 0, 0, 1, 0, 0.5, 1, 0.5, 0, 0, false) == null);
    }

    @Test
    void wireVisuallyConnectsToComparatorSide() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, wireState().getId());
        world.put(0, 64, -1, wireState().getId());
        world.put(1, 64, 0, state("comparator")
                .with(Properties.FACING, Direction.NORTH).getId());

        RedstoneWireNetwork.update(world, 0, 64, 0);

        assertEquals(de.skyengine.game.world.block.state.RedstoneSide.SIDE,
                Blocks.getState(world.getBlock(0, 64, 0)).get(Properties.WIRE_EAST));
    }

    @Test
    void wireDoesNotVisuallyConnectToRepeaterSide() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, wireState().getId());
        world.put(0, 64, -1, wireState().getId());
        world.put(1, 64, 0, state("repeater")
                .with(Properties.FACING, Direction.NORTH).getId());

        RedstoneWireNetwork.update(world, 0, 64, 0);

        assertEquals(de.skyengine.game.world.block.state.RedstoneSide.NONE,
                Blocks.getState(world.getBlock(0, 64, 0)).get(Properties.WIRE_EAST));
    }

    private static TestWorld steppedWireWorld(BlockState blockAboveLowerWire) {
        TestWorld world = new TestWorld();
        BlockState wire = wireState();
        world.put(0, 63, 0, state("stone").getId());
        world.put(1, 64, 0, state("stone").getId());
        world.put(-1, 64, 0, state("redstone_block").getId());
        world.put(0, 64, 0, wire.getId());
        world.put(0, 65, 0, blockAboveLowerWire.getId());
        world.put(1, 64, 0, state("stone").getId());
        world.put(1, 65, 0, wire.getId());
        return world;
    }

    private static TestWorld climbShapeWorld(BlockState support) {
        TestWorld world = new TestWorld();
        BlockState wire = wireState();
        world.put(0, 63, 0, state("stone").getId());
        world.put(0, 64, 0, wire.getId());
        world.put(0, 64, -1, wire.getId());
        world.put(1, 64, 0, support.getId());
        world.put(1, 65, 0, wire.getId());
        return world;
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static BlockState wireState() {
        BlockState wire = BlockStateCodec.decode(
                "skyengine:redstone_wire[east=none,north=none,power=0,south=none,west=none]");
        if (wire == null) throw new IllegalStateException("Redstone-Staub fehlt in der Test-Registry");
        return wire;
    }

    private static void assertConnected(TestWorld world, int x, int y, int z, String message) {
        BlockState state = Blocks.getState(world.getBlock(x, y, z));
        boolean connected = false;
        for (Direction direction : Direction.horizontalValues()) {
            connected |= state.get(Properties.wireSide(direction)).isConnected();
        }
        assertTrue(connected, message);
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(2048);
        private final CountDownLatch solverEntered;
        private final CountDownLatch releaseSolver;
        private final AtomicInteger reads = new AtomicInteger();

        TestWorld() {
            this(null, null);
        }

        TestWorld(CountDownLatch solverEntered, CountDownLatch releaseSolver) {
            super("__redstone_wire_test", level(), null, null);
            this.solverEntered = solverEntered;
            this.releaseSolver = releaseSolver;
        }

        void put(int x, int y, int z, int stateId) {
            this.blocks.put(BlockPos.asLong(x, y, z), stateId);
        }

        @Override
        public int getBlock(int x, int y, int z) {
            /* Der erste Read ist die Vorprüfung vor dem Guard, der zweite liegt sicher im
               Solver. So hält der Test Welt 1 deterministisch bei aktivem Guard an. */
            if (this.solverEntered != null && this.reads.incrementAndGet() == 2) {
                this.solverEntered.countDown();
                try {
                    if (!this.releaseSolver.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Freigabe des ersten Solvers fehlt");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Warten auf Solver-Freigabe unterbrochen", e);
                }
            }
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            this.put(x, y, z, block);
            return true;
        }

        @Override
        public void updateBlockStateAt(int x, int y, int z) {
            BlockState state = Blocks.getState(this.getBlock(x, y, z));
            if (!state.isAir()) {
                state.getBlock().getStateForNeighborUpdate(this, x, y, z, state);
            }
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
