package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.World;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HopperBlockEntityTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void emptyTargetGetsEightTicksBeforeItsOwnTickAndThenCountsDown() {
        TestWorld world = new TestWorld();
        HopperBlockEntity source = world.addHopper(0, Direction.EAST);
        HopperBlockEntity target = world.addHopper(1, Direction.DOWN);
        source.getInventory().set(0, stone(1));
        world.gameTime = 1;

        source.tick();

        assertEquals(8, source.getCooldown());
        assertEquals(8, target.getCooldown());
        assertTrue(source.getInventory().get(0).isEmpty());
        assertEquals(1, target.getInventory().get(0).getCount());

        target.tick();
        assertEquals(7, target.getCooldown());
        assertTrue(world.changedX.contains(0));
        assertTrue(world.changedX.contains(1));
    }

    @Test
    void targetThatAlreadyTickedGetsSevenTickPhaseCorrection() {
        TestWorld world = new TestWorld();
        HopperBlockEntity source = world.addHopper(0, Direction.EAST);
        HopperBlockEntity target = world.addHopper(1, Direction.DOWN);
        source.getInventory().set(0, stone(1));
        world.gameTime = 1;

        target.tick();
        source.tick();

        assertEquals(7, target.getCooldown());
    }

    @Test
    void normalHopperMovesOneItemEveryEightGameTicks() {
        TestWorld world = new TestWorld();
        HopperBlockEntity source = world.addHopper(0, Direction.EAST);
        HopperBlockEntity target = world.addHopper(1, Direction.DOWN);
        source.getInventory().set(0, stone(2));

        world.gameTime = 1;
        source.tick();
        assertEquals(1, target.getInventory().get(0).getCount());

        for (int tick = 2; tick <= 8; tick++) {
            world.gameTime = tick;
            source.tick();
            assertEquals(1, target.getInventory().get(0).getCount(),
                    "vor Ablauf von acht Ticks darf kein zweites Item wandern");
        }

        world.gameTime = 9;
        source.tick();
        assertEquals(2, target.getInventory().get(0).getCount());
    }

    @Test
    void genericNeighborStateChangeMakesHopperEnabledVisibleToWatchingObserver() {
        TestWorld world = new TestWorld();
        BlockState hopper = state("hopper")
                .with(Properties.FACING_ALL, Direction.DOWN)
                .with(Properties.ENABLED, true);
        BlockState observer = state("observer")
                .with(Properties.FACING_ALL, Direction.WEST)
                .with(Properties.POWERED, false);
        world.putBlock(0, hopper);
        world.putBlock(1, observer);
        world.putBlock(-1, state("redstone_block"));
        observer.getBlock().onPlaced(world, 1, 64, 0, observer);

        BlockState disabled = hopper.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, hopper);
        assertFalse(disabled.get(Properties.ENABLED));
        world.putBlock(0, disabled);
        disabled.getBlock().onStateChangedByNeighborUpdate(world, 0, 64, 0, hopper, disabled);

        assertEquals(1, world.scheduledTicks);
        assertEquals(2, world.lastScheduledDelay);
    }

    @Test
    void poweredPlacementStartsEnabledAndLocksInOnPlacedPhase() {
        TestWorld world = new TestWorld();
        world.putBlock(-1, state("redstone_block"));
        BlockState hopper = state("hopper").getBlock().getPlacementState(
                world, 0, 64, 0, 0, 1, 0, 0.5, 1, 0.5, 0, 0, false);

        assertTrue(hopper.get(Properties.ENABLED),
                "Vanillas getStateForPlacement schreibt immer enabled=true");

        world.putBlock(0, hopper);
        hopper.getBlock().onPlaced(world, 0, 64, 0, hopper);

        assertFalse(Blocks.getState(world.getBlock(0, 64, 0)).get(Properties.ENABLED));
    }

    @Test
    void suctionStartsAtElevenSixteenthsInsideHopper() {
        TestWorld world = new TestWorld();
        HopperBlockEntity hopper = world.addHopper(0, Direction.DOWN);
        ItemEntity tooLow = world.addItem(0.5, 64.4, 0.5);

        world.gameTime = 1;
        hopper.tick();

        assertFalse(tooLow.isRemoved());
        assertTrue(hopper.getInventory().get(0).isEmpty());

        ItemEntity inRange = world.addItem(0.5, 64.5, 0.5);
        world.gameTime = 2;
        hopper.tick();
        assertTrue(inRange.isRemoved());
        assertEquals(1, hopper.getInventory().get(0).getCount());
    }

    @Test
    void fullCollisionBlockAbovePreventsItemEntitySuction() {
        TestWorld world = new TestWorld();
        HopperBlockEntity hopper = world.addHopper(0, Direction.DOWN);
        world.putBlock(0, 65, 0, state("stone"));
        ItemEntity item = world.addItem(0.5, 65.0, 0.5);

        world.gameTime = 1;
        hopper.tick();

        assertFalse(item.isRemoved());
        assertTrue(hopper.getInventory().get(0).isEmpty());
    }

    @Test
    void partialCollisionBlockAboveDoesNotPreventItemEntitySuction() {
        TestWorld world = new TestWorld();
        HopperBlockEntity hopper = world.addHopper(0, Direction.DOWN);
        world.putBlock(0, 65, 0, state("stone_slab").with(Properties.SLAB_TYPE, SlabType.BOTTOM));
        ItemEntity item = world.addItem(0.5, 65.5, 0.5);

        world.gameTime = 1;
        hopper.tick();

        assertTrue(item.isRemoved());
        assertEquals(1, hopper.getInventory().get(0).getCount());
    }

    @Test
    void partiallyAbsorbedItemEntityDoesNotStartVanillaCooldown() {
        TestWorld world = new TestWorld();
        HopperBlockEntity hopper = world.addHopper(0, Direction.DOWN);
        hopper.getInventory().set(0, stone(63));
        for (int slot = 1; slot < HopperBlockEntity.SLOTS; slot++) {
            hopper.getInventory().set(slot, stone(64));
        }
        ItemEntity item = world.addItem(0.5, 65.0, 0.5);
        item.getStack().setCount(2);

        world.gameTime = 1;
        hopper.tick();

        assertFalse(item.isRemoved());
        assertEquals(1, item.getStack().getCount());
        assertEquals(64, hopper.getInventory().get(0).getCount());
        assertEquals(0, hopper.getCooldown(),
                "Vanilla meldet partielle ItemEntity-Aufnahme nicht als erfolgreichen Transfer");
    }

    @Test
    void fullHopperDoesNotProbeAndMutateContainerAbove() {
        TestWorld world = new TestWorld();
        HopperBlockEntity receiver = world.addHopper(0, 64, Direction.DOWN);
        HopperBlockEntity source = world.addHopper(0, 65, Direction.DOWN);
        for (int slot = 0; slot < HopperBlockEntity.SLOTS; slot++) {
            receiver.getInventory().set(slot, stone(64));
        }
        source.getInventory().set(0, stone(1));
        world.modifiedCalls = 0;

        world.gameTime = 1;
        receiver.tick();

        assertEquals(0, world.modifiedCalls,
                "ein voller Hopper darf den Quellcontainer nicht probeweise extrahieren");
        assertEquals(1, source.getInventory().get(0).getCount());
        assertEquals(0, receiver.getCooldown());
    }

    @Test
    void doesNotBlockHoppersFlagOverridesFullCollisionBlock() {
        Block exempt = new Block(Identifier.of("skyengine:test_beehive"),
                Block.Settings.create().doesNotBlockHoppers(true));

        assertTrue(exempt.getDefaultState().getCollisionShape().isFullCube());
        assertFalse(HopperBlockEntity.blocksItemEntitySuction(exempt.getDefaultState()));
        assertTrue(HopperBlockEntity.blocksItemEntitySuction(state("stone")));
    }

    private static ItemStack stone(int count) {
        return new ItemStack(Items.get(Identifier.of("skyengine:stone")), count);
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

        private final LongIntMap blocks = new LongIntMap(16);
        private final Map<Long, BlockEntity> blockEntities = new HashMap<>();
        private final List<Entity> entities = new ArrayList<>();
        private final Set<Integer> changedX = new HashSet<>();
        private long gameTime;
        private int scheduledTicks;
        private int lastScheduledDelay;
        private int modifiedCalls;

        TestWorld() {
            super("__hopper_test", level(), null, null);
            try {
                Field managerField = World.class.getDeclaredField("chunkManager");
                managerField.setAccessible(true);
                ChunkManager manager = (ChunkManager) managerField.get(this);
                @SuppressWarnings("unchecked")
                Map<Long, Chunk> chunks = (Map<Long, Chunk>) CHUNKS_FIELD.get(manager);
                chunks.put(Chunk.key(0, 0), new Chunk(0, 0));
                chunks.put(Chunk.key(-1, 0), new Chunk(-1, 0));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Test-Chunks konnten nicht installiert werden", e);
            }
        }

        HopperBlockEntity addHopper(int x, Direction facing) {
            return this.addHopper(x, 64, facing);
        }

        HopperBlockEntity addHopper(int x, int y, Direction facing) {
            BlockState state = state("hopper")
                    .with(Properties.FACING_ALL, facing)
                    .with(Properties.ENABLED, true);
            putBlock(x, y, 0, state);
            HopperBlockEntity hopper = new HopperBlockEntity(
                    BlockEntities.HOPPER, new BlockPos(x, y, 0));
            hopper.setWorld(this);
            this.blockEntities.put(BlockPos.asLong(x, y, 0), hopper);
            return hopper;
        }

        void putBlock(int x, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, 64, 0), state.getId());
        }

        void putBlock(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        ItemEntity addItem(double x, double y, double z) {
            ItemEntity item = new ItemEntity(stone(1));
            item.setPosition(x, y, z);
            this.entities.add(item);
            return item;
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
        public BlockEntity getBlockEntity(int x, int y, int z) {
            return this.blockEntities.get(BlockPos.asLong(x, y, z));
        }

        @Override
        public long getGameTime() {
            return this.gameTime;
        }

        @Override
        public void markChunkModified(int x, int z) {
            this.changedX.add(x);
            this.modifiedCalls++;
        }

        @Override
        public void updateComparatorOutputs(int x, int y, int z) {
        }

        @Override
        public void forEachEntityNearby(double x, double z, int chunkRadius, Consumer<Entity> action) {
            for (Entity entity : this.entities) action.accept(entity);
        }

        @Override
        public boolean isTickScheduled(int x, int y, int z) {
            return false;
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delayTicks) {
            this.scheduledTicks++;
            this.lastScheduledDelay = delayTicks;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "hopper-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
