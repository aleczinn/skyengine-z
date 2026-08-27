package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.PistonType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.redstone.RedstoneWireNetwork;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(1, world.eventCount);
        assertEquals(0, world.eventId);
        assertEquals(5, world.eventParam);
    }

    @Test
    void blockedStructureDoesNotQueueVanillaExtendEvent() {
        TestWorld world = new TestWorld();
        BlockState piston = state("piston")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, false);
        world.put(0, 64, 0, piston);
        world.put(-1, 64, 0, state("redstone_block"));
        world.put(1, 64, 0, state("obsidian"));

        piston.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, piston);

        assertEquals(0, world.eventCount);
    }

    @Test
    void fallingEdgeQueuesVanillaContractEventWithDirectionParameter() {
        TestWorld world = new TestWorld();
        BlockState piston = state("sticky_piston")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, true);
        world.put(0, 64, 0, piston);

        piston.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, piston);

        assertEquals(1, world.eventCount);
        assertEquals(1, world.eventId);
        assertEquals(5, world.eventParam);
    }

    @Test
    void halfProgressCargoContractsOutsideWorldTick() {
        TestWorld world = new TestWorld();
        BlockState piston = extendedStickyPiston(world);
        world.addHalfProgressCargo();

        piston.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, piston);

        assertEquals(1, world.eventId);
    }

    @Test
    void halfProgressCargoDropsDuringWorldTick() {
        TestWorld world = new TestWorld();
        BlockState piston = extendedStickyPiston(world);
        world.addHalfProgressCargo();
        world.handlingTick = true;

        piston.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, piston);

        assertEquals(2, world.eventId);
    }

    @Test
    void completedCargoContractsWhenSourceFinishesInBlockEntityPhase() {
        TestWorld world = new TestWorld();
        BlockState piston = extendedStickyPiston(world);
        PistonMovingBlockEntity cargo = world.addCargoAtProgress(1.0);

        cargo.tick();
        piston.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, piston);

        assertEquals(state("stone").getId(), world.getBlock(2, 64, 0));
        assertEquals(1, world.eventId,
                "nach der BE-Phase muss die fallende Flanke normal einziehen statt Fracht zu droppen");
    }

    @Test
    void retractSourceOccupiesVanillaBasePosition() {
        TestWorld world = new TestWorld();
        BlockState piston = state("piston")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, true);
        world.put(0, 64, 0, piston);
        world.put(1, 64, 0, state("piston_head")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.PISTON_TYPE, PistonType.NORMAL));

        piston.getBlock().onBlockEvent(world, 0, 64, 0, piston, 1, 5);

        BlockState movingState = Blocks.getState(world.getBlock(0, 64, 0));
        assertEquals(Blocks.getState(Blocks.MOVING_PISTON).getBlock(), movingState.getBlock());
        assertTrue(movingState.get(Properties.RETRACTING_SOURCE));
        assertEquals(Direction.EAST, movingState.get(Properties.FACING_ALL));
        assertEquals(PistonType.NORMAL, movingState.get(Properties.PISTON_TYPE));
        assertTrue(movingState.getModel().length > 0,
                "die stationaere Basis muss aus dem Chunk-Mesh statt ein zweites Mal aus dem BE-Renderer kommen");
        assertEquals(0, Blocks.getState(Blocks.MOVING_PISTON).getModel().length,
                "normale Fracht- und Extend-States duerfen keine statische Renderhuelle erhalten");
        PistonMovingBlockEntity source = (PistonMovingBlockEntity) world.getBlockEntity(0, 64, 0);
        assertTrue(source.isSource());
        assertFalse(source.isExtending());
        BlockState movedBase = Blocks.getState(source.getMovedStateId());
        assertEquals(Direction.EAST, movedBase.get(Properties.FACING_ALL));
        assertFalse(movedBase.get(Properties.EXTENDED));
        assertEquals(Blocks.AIR, world.getBlock(1, 64, 0));
    }

    @Test
    void stickyRetractKeepsCargoSeparateFromSource() {
        TestWorld world = new TestWorld();
        BlockState piston = extendedStickyPiston(world);
        world.put(1, 64, 0, state("piston_head")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.PISTON_TYPE, PistonType.STICKY));
        world.put(2, 64, 0, state("stone"));

        piston.getBlock().onBlockEvent(world, 0, 64, 0, piston, 1, 5);

        PistonMovingBlockEntity source = (PistonMovingBlockEntity) world.getBlockEntity(0, 64, 0);
        PistonMovingBlockEntity cargo = (PistonMovingBlockEntity) world.getBlockEntity(1, 64, 0);
        assertTrue(source.isSource());
        assertFalse(cargo.isSource());
        assertEquals(state("sticky_piston").getBlock(),
                Blocks.getState(source.getMovedStateId()).getBlock());
        assertEquals(state("stone").getId(), cargo.getMovedStateId());
        assertEquals(Blocks.AIR, world.getBlock(2, 64, 0));
    }

    @Test
    void observerClockAssemblyRetractsAsOneSynchronizedMovementGroup() {
        TestWorld world = new TestWorld();
        BlockState piston = state("sticky_piston")
                .with(Properties.FACING_ALL, Direction.SOUTH)
                .with(Properties.EXTENDED, true);
        world.put(0, 64, 0, piston);
        world.put(0, 64, 1, state("piston_head")
                .with(Properties.FACING_ALL, Direction.SOUTH)
                .with(Properties.PISTON_TYPE, PistonType.STICKY));
        world.put(0, 64, 2, state("slime_block"));
        world.put(1, 64, 2, state("slime_block"));
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.SOUTH)
                .with(Properties.POWERED, false);
        world.put(1, 64, 1, observer);

        piston.getBlock().onBlockEvent(world, 0, 64, 0, piston, 1, 3);

        PistonMovingBlockEntity frontSlime = movingAt(world, 0, 64, 1);
        PistonMovingBlockEntity sideSlime = movingAt(world, 1, 64, 1);
        PistonMovingBlockEntity movedObserver = movingAt(world, 1, 64, 0);
        assertEquals(state("slime_block").getId(), frontSlime.getMovedStateId());
        assertEquals(state("slime_block").getId(), sideSlime.getMovedStateId());
        assertEquals(observer.getId(), movedObserver.getMovedStateId());

        PistonMovingBlockEntity source = movingAt(world, 0, 64, 0);
        frontSlime.tick();
        sideSlime.tick();
        movedObserver.tick();
        source.tick();
        assertEquals(0.5f, frontSlime.getProgress(1.0f));
        assertEquals(frontSlime.getProgress(1.0f), sideSlime.getProgress(1.0f));
        assertEquals(frontSlime.getProgress(1.0f), movedObserver.getProgress(1.0f));
    }

    @Test
    void movedObserverKeepsExactAssemblyClockRunning() throws Exception {
        ClockWorld world = new ClockWorld();
        BlockState piston = state("sticky_piston")
                .with(Properties.FACING_ALL, Direction.SOUTH)
                .with(Properties.EXTENDED, true);
        world.set(4, 64, 4, piston);
        world.set(4, 64, 5, state("piston_head")
                .with(Properties.FACING_ALL, Direction.SOUTH)
                .with(Properties.PISTON_TYPE, PistonType.STICKY));
        world.set(4, 64, 6, state("slime_block"));
        world.set(5, 64, 6, state("slime_block"));
        world.set(5, 64, 5, state("observer")
                .with(Properties.FACING_ALL, Direction.SOUTH)
                .with(Properties.POWERED, false));
        world.set(4, 64, 3, state("stone"));
        world.set(5, 64, 3, state("stone"));
        world.set(4, 65, 3, RedstoneWireNetwork.toCross(state("redstone_wire")));
        world.set(5, 65, 3, RedstoneWireNetwork.toCross(state("redstone_wire")));

        piston.getBlock().onBlockEvent(world, 4, 64, 4, piston, 1, 3);
        for (int i = 0; i < 24; i++) world.advanceClockTick();

        assertTrue(world.extendEvents >= 2,
                "der verschobene Observer muss den Kolben wiederholt neu ausfahren: " + world.trace);
        assertTrue(world.contractEvents >= 1,
                "der Observer-Puls muss auch wieder abfallen: " + world.trace);
        assertTrue(world.retractedLandings >= 2,
                "die komplette Baugruppe muss mindestens zweimal eingefahren landen: " + world.trace);
        assertTrue(world.extendedLandings >= 2,
                "die komplette Baugruppe muss mindestens zweimal ausgefahren landen: " + world.trace);
        for (int z = 7; z <= 15; z++) {
            assertEquals(Blocks.AIR, world.getBlock(4, 64, z),
                    "der Slime-Verbund darf keine Spur hinterlassen: " + world.trace);
            assertEquals(Blocks.AIR, world.getBlock(5, 64, z),
                    "der Observer-Zweig darf keine Spur hinterlassen: " + world.trace);
        }
    }

    @Test
    void slimeBranchesFollowVanillaDirectionEnumOrder() {
        TestWorld world = new TestWorld();
        world.put(1, 64, 0, state("slime_block"));
        world.put(1, 63, 0, state("stone"));
        world.put(1, 65, 0, state("stone"));

        PistonResolver.Result result = PistonResolver.resolveExtend(
                world, 0, 64, 0, Direction.EAST);

        assertFalse(result.blocked());
        assertArrayEquals(new long[] {
                BlockPos.asLong(1, 64, 0),
                BlockPos.asLong(1, 63, 0),
                BlockPos.asLong(1, 65, 0)
        }, result.moves());
    }

    @Test
    void slimeMovesEveryRailVariantInsteadOfDestroyingIt() {
        for (String rail : List.of("rail", "powered_rail", "detector_rail", "activator_rail")) {
            TestWorld world = new TestWorld();
            world.put(1, 64, 0, state("slime_block"));
            world.put(1, 65, 0, state(rail));

            PistonResolver.Result result = PistonResolver.resolveExtend(
                    world, 0, 64, 0, Direction.EAST);

            assertFalse(result.blocked(), rail);
            assertArrayEquals(new long[] {
                    BlockPos.asLong(1, 64, 0),
                    BlockPos.asLong(1, 65, 0)
            }, result.moves(), rail);
            assertEquals(0, result.destroys().length, rail);
        }
    }

    @Test
    void collidingStickyLineIsReorderedLikeVanilla() {
        List<Long> positions = new ArrayList<>(List.of(10L, 20L, 30L, 40L));

        int branchingEnd = PistonResolver.reorderListAtCollision(positions, 2, 1);

        assertEquals(List.of(10L, 30L, 40L, 20L), positions);
        assertEquals(3, branchingEnd,
                "Vanilla verzweigt bis collisionIndex + addedCount inklusive");
    }

    private static BlockState extendedStickyPiston(TestWorld world) {
        BlockState piston = state("sticky_piston")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, true);
        world.put(0, 64, 0, piston);
        return piston;
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("voxelstories:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static PistonMovingBlockEntity movingAt(TestWorld world, int x, int y, int z) {
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        assertTrue(blockEntity instanceof PistonMovingBlockEntity,
                "Moving-Piston-BE fehlt bei " + x + "," + y + "," + z);
        return (PistonMovingBlockEntity) blockEntity;
    }

    private static final class TestWorld extends Dimension {
        private final LongIntMap blocks = new LongIntMap(16);
        private final Map<Long, BlockEntity> blockEntities = new HashMap<>();
        private int eventId = -1;
        private int eventParam = -1;
        private int eventCount;
        private boolean handlingTick;

        private TestWorld() {
            super("__piston_behavior_test", level(), null, null);
        }

        private void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        private void addHalfProgressCargo() {
            this.addCargoAtProgress(0.5);
        }

        private PistonMovingBlockEntity addCargoAtProgress(double progress) {
            int x = 2, y = 64, z = 0;
            this.put(x, y, z, state("moving_piston"));
            PistonMovingBlockEntity cargo = new PistonMovingBlockEntity(
                    BlockEntities.PISTON_MOVING, new BlockPos(x, y, z));
            cargo.setWorld(this);
            cargo.configure(state("stone").getId(), Direction.EAST, true, false, false);
            DataTag tag = new DataTag();
            cargo.save(tag);
            tag.putDouble("progress", progress);
            cargo.load(tag);
            this.blockEntities.put(BlockPos.asLong(x, y, z), cargo);
            return cargo;
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public BlockEntity getBlockEntity(int x, int y, int z) {
            return this.blockEntities.get(BlockPos.asLong(x, y, z));
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            long pos = BlockPos.asLong(x, y, z);
            this.blocks.put(pos, block);
            this.blockEntities.remove(pos);
            if (Blocks.getState(block).getBlock()
                    == Blocks.getState(Blocks.MOVING_PISTON).getBlock()) {
                PistonMovingBlockEntity moving = new PistonMovingBlockEntity(
                        BlockEntities.PISTON_MOVING, new BlockPos(x, y, z));
                moving.setWorld(this);
                this.blockEntities.put(pos, moving);
            }
            return true;
        }

        @Override
        public void updateNeighbors(int x, int y, int z) {
        }

        @Override
        public void markChunkModified(int x, int z) {
        }

        @Override
        public boolean isHandlingTick() {
            return this.handlingTick;
        }

        @Override
        public boolean isPositionEditable(int x, int y, int z) {
            return true;
        }

        @Override
        public void enqueueBlockEvent(int x, int y, int z, int eventId, int eventParam) {
            this.eventCount++;
            this.eventId = eventId;
            this.eventParam = eventParam;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "piston-behavior-test";
            level.seed = 1;

            return level;
        }
    }

    private static final class ClockWorld extends Dimension {
        private static final Field CHUNKS_FIELD;
        private static final Field GAME_TIME_FIELD;
        private static final Field HANDLING_TICK_FIELD;
        private static final Method TICK_SCHEDULED;
        private static final Method PROCESS_BLOCK_EVENTS;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                GAME_TIME_FIELD = Dimension.class.getDeclaredField("gameTime");
                GAME_TIME_FIELD.setAccessible(true);
                HANDLING_TICK_FIELD = Dimension.class.getDeclaredField("handlingTick");
                HANDLING_TICK_FIELD.setAccessible(true);
                TICK_SCHEDULED = Dimension.class.getDeclaredMethod("tickScheduled");
                TICK_SCHEDULED.setAccessible(true);
                PROCESS_BLOCK_EVENTS = Dimension.class.getDeclaredMethod("processBlockEvents");
                PROCESS_BLOCK_EVENTS.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final Chunk chunk = new Chunk(0, 0);
        private final List<String> trace = new ArrayList<>();
        private int extendEvents;
        private int contractEvents;
        private int retractedLandings;
        private int extendedLandings;
        private boolean wasRetracted;
        private boolean wasExtended;

        @SuppressWarnings("unchecked")
        private ClockWorld() throws ReflectiveOperationException {
            super("__piston_clock_test", clockLevel(), null, null);
            this.chunk.status = ChunkStatus.READY;
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.getChunkManager()))
                    .put(Chunk.key(0, 0), this.chunk);
        }

        private void set(int x, int y, int z, BlockState state) {
            if (!this.setBlock(x, y, z, state.getId(), false)) {
                throw new AssertionError("Block konnte nicht gesetzt werden: " + x + "," + y + "," + z);
            }
        }

        private void advanceClockTick() throws ReflectiveOperationException {
            long time = GAME_TIME_FIELD.getLong(this) + 1;
            GAME_TIME_FIELD.setLong(this, time);
            HANDLING_TICK_FIELD.setBoolean(this, true);
            TICK_SCHEDULED.invoke(this);
            PROCESS_BLOCK_EVENTS.invoke(this);
            HANDLING_TICK_FIELD.setBoolean(this, false);
            List<BlockEntity> moving = new ArrayList<>(this.chunk.blockEntities());
            for (BlockEntity blockEntity : moving) blockEntity.tick();
            boolean retracted = this.isAssemblyAt(4);
            boolean extended = this.isAssemblyAt(5);
            if (retracted && !this.wasRetracted) this.retractedLandings++;
            if (extended && !this.wasExtended) this.extendedLandings++;
            this.wasRetracted = retracted;
            this.wasExtended = extended;
            this.trace.add(time + ":base="
                    + Blocks.getState(this.getBlock(4, 64, 4)).getBlock().getIdentifier()
                    + ",obs4=" + this.observerPowerAt(5, 64, 4)
                    + ",obs5=" + this.observerPowerAt(5, 64, 5)
                    + ",wire=" + Blocks.getState(this.getBlock(5, 65, 3)).get(Properties.POWER));
        }

        private boolean isAssemblyAt(int observerZ) {
            BlockState observer = Blocks.getState(this.getBlock(5, 64, observerZ));
            return observer.getBlock() == state("observer").getBlock()
                    && Blocks.getState(this.getBlock(4, 64, observerZ + 1)).getBlock()
                    == state("slime_block").getBlock()
                    && Blocks.getState(this.getBlock(5, 64, observerZ + 1)).getBlock()
                    == state("slime_block").getBlock();
        }

        private String observerPowerAt(int x, int y, int z) {
            BlockState observer = state("observer");
            BlockState current = Blocks.getState(this.getBlock(x, y, z));
            return current.getBlock() == observer.getBlock()
                    ? String.valueOf(current.get(Properties.POWERED)) : "-";
        }

        @Override
        public void enqueueBlockEvent(int x, int y, int z, int eventId, int eventParam) {
            if (x == 4 && y == 64 && z == 4) {
                if (eventId == 0) this.extendEvents++;
                if (eventId == 1 || eventId == 2) this.contractEvents++;
            }
            super.enqueueBlockEvent(x, y, z, eventId, eventParam);
        }

        private static LevelData clockLevel() {
            LevelData level = new LevelData();
            level.name = "piston-clock-test";
            level.seed = 1;

            return level;
        }
    }
}
