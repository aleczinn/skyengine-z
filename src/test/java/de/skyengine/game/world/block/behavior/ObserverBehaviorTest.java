package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ObserverBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void placementStaysSilentAndLaterGenericStateChangePulses() {
        TestWorld world = new TestWorld();
        BlockState fence = state("oak_fence").with(Properties.NORTH, false);
        BlockState connectedFence = fence.with(Properties.NORTH, true);
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, false);
        world.put(0, fence);
        world.put(1, observer);

        observer.getBlock().onPlaced(world, 1, 64, 0, observer);
        assertEquals(0, world.scheduledTicks, "bloßes Platzieren darf nicht pulsen");

        world.put(0, connectedFence);
        connectedFence.getBlock().onStateChangedByNeighborUpdate(
                world, 0, 64, 0, fence, connectedFence);

        assertEquals(1, world.scheduledTicks);
        assertEquals(2, world.lastDelay);
    }

    @Test
    void frontShapeUpdatePulsesEvenWhenNeighborStateDidNotChange() {
        TestWorld world = new TestWorld();
        BlockState stone = state("stone");
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, false);

        observer.getBlock().getStateForNeighborUpdate(
                world, 1, 64, 0, observer, Direction.WEST, stone);

        assertEquals(1, world.scheduledTicks);
        assertEquals(2, world.lastDelay);
    }

    @Test
    void shapeUpdateFromBackDoesNotPulse() {
        TestWorld world = new TestWorld();
        BlockState stone = state("stone");
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, false);

        observer.getBlock().getStateForNeighborUpdate(
                world, 1, 64, 0, observer, Direction.EAST, stone);

        assertEquals(0, world.scheduledTicks);
    }

    @Test
    void worldNeighborRingCarriesDirectionToObserver() {
        TestWorld frontWorld = new TestWorld();
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, false);
        frontWorld.put(0, state("stone"));
        frontWorld.put(1, observer);
        frontWorld.updateNeighbors(0, 64, 0);
        assertEquals(1, frontWorld.scheduledTicks);

        TestWorld backWorld = new TestWorld();
        backWorld.put(1, observer);
        backWorld.put(2, state("stone"));
        backWorld.updateNeighbors(2, 64, 0);
        assertEquals(0, backWorld.scheduledTicks);
    }

    @Test
    void removingPoweredPulseNotifiesStrongTargetOnlyWhileOffTickIsPending() {
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, true);
        TestWorld pulsing = new TestWorld();
        pulsing.tickScheduled = true;

        observer.getBlock().onRemoved(pulsing, 1, 64, 0, observer, Blocks.getState(Blocks.AIR));

        assertEquals(1, pulsing.neighborUpdates);
        assertEquals(2, pulsing.lastNeighborX,
                "FACING=WEST gibt das Signal nach EAST in den Ausgangsblock ab");

        TestWorld artificial = new TestWorld();
        observer.getBlock().onRemoved(artificial, 1, 64, 0, observer, Blocks.getState(Blocks.AIR));
        assertEquals(0, artificial.neighborUpdates,
                "Vanilla benachrichtigt ohne ausstehenden Abschalt-Tick nicht extra");
    }

    @Test
    void poweredObserverMovedWithoutItsOldTickTurnsOffImmediately() {
        TestWorld world = new TestWorld();
        BlockState powered = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, true);
        world.put(1, powered);

        powered.getBlock().onMovedByPiston(world, 1, 64, 0, powered, Direction.NORTH);

        assertEquals(false, Blocks.getState(world.getBlock(1, 64, 0)).get(Properties.POWERED));
        assertEquals(0, world.scheduledTicks);
        assertEquals(1, world.neighborUpdates);
        assertEquals(2, world.lastNeighborX);
    }

    @Test
    void unpoweredMovedObserverStillStartsItsArrivalPulse() {
        TestWorld world = new TestWorld();
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, false);

        observer.getBlock().onMovedByPiston(world, 1, 64, 0, observer, Direction.NORTH);

        assertEquals(1, world.scheduledTicks);
        assertEquals(2, world.lastDelay);
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends World {
        private static final Field CHUNKS_FIELD;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final LongIntMap blocks = new LongIntMap(8);
        private int scheduledTicks;
        private int lastDelay;
        private boolean tickScheduled;
        private int neighborUpdates;
        private int lastNeighborX;

        TestWorld() {
            super("__observer_test", level(), null, null);
            try {
                @SuppressWarnings("unchecked")
                Map<Long, Chunk> chunks = (Map<Long, Chunk>) CHUNKS_FIELD.get(this.getChunkManager());
                Chunk chunk = new Chunk(0, 0);
                chunk.status = ChunkStatus.READY;
                chunks.put(Chunk.key(0, 0), chunk);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Test-Chunk konnte nicht installiert werden", e);
            }
        }

        void put(int x, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, 64, 0), state.getId());
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
        public boolean isTickScheduled(int x, int y, int z) {
            return this.tickScheduled;
        }

        @Override
        public boolean isTickScheduled(int x, int y, int z, Identifier expectedBlock) {
            return this.tickScheduled;
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delayTicks) {
            this.scheduledTicks++;
            this.lastDelay = delayTicks;
        }

        @Override
        public void updateNeighbors(int x, int y, int z) {
            this.neighborUpdates++;
            this.lastNeighborX = x;
            super.updateNeighbors(x, y, z);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "observer-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
