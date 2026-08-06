package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.PistonReaction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ComparatorMode;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ComparatorBlockEntity;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.tick.TickPriority;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ComparatorBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void subtractModeReadsSideWirePowerEvenWithoutVisualConnection() {
        TestWorld world = new TestWorld();
        BlockState comparator = state("comparator")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.MODE, ComparatorMode.SUBTRACT)
                .with(Properties.POWERED, false);
        Direction rear = Direction.WEST;
        Direction side = Direction.EAST.rotateYCW();
        world.put(rear.offsetX(), 64, rear.offsetZ(), state("redstone_block"));
        world.put(side.offsetX(), 64, side.offsetZ(),
                state("redstone_wire").with(Properties.POWER, 14));

        assertEquals(1, ComparatorBehavior.computeOutput(world, 0, 64, 0, comparator));
    }

    @Test
    void subtractModeAcceptsLeverAsSideSignalSource() {
        TestWorld world = new TestWorld();
        BlockState comparator = state("comparator")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.MODE, ComparatorMode.SUBTRACT)
                .with(Properties.POWERED, false);
        Direction rear = Direction.WEST;
        Direction side = Direction.EAST.rotateYCW();
        BlockState lever = state("lever").with(Properties.POWERED, true);
        world.put(rear.offsetX(), 64, rear.offsetZ(), state("redstone_block"));
        world.put(side.offsetX(), 64, side.offsetZ(), lever);

        assertTrue(lever.getBlock().isRedstoneSignalSource());
        assertFalse(state("stone").getBlock().isRedstoneSignalSource());
        assertEquals(0, ComparatorBehavior.computeOutput(world, 0, 64, 0, comparator));
    }

    @Test
    void readsItemFrameThroughExactlyOneConductor() {
        TestWorld world = new TestWorld();
        world.itemFrameSignal = 6;
        BlockState comparator = state("comparator")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.MODE, ComparatorMode.COMPARE)
                .with(Properties.POWERED, false);
        world.put(-1, 64, 0, state("stone"));

        assertEquals(6, ComparatorBehavior.computeOutput(world, 0, 64, 0, comparator));
    }

    @Test
    void schedulesHighPriorityWhenDrivingAnotherForwardDiode() {
        TestWorld world = new TestWorld();
        BlockState comparator = state("comparator")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.MODE, ComparatorMode.COMPARE)
                .with(Properties.POWERED, false);
        world.put(0, 64, 0, comparator);
        world.put(-1, 64, 0, state("redstone_block"));
        world.put(1, 64, 0, state("repeater").with(Properties.FACING, Direction.EAST));

        comparator.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, comparator);

        assertEquals(TickPriority.HIGH, world.scheduledPriority);
    }

    @Test
    void compareTickNotifiesOutputEvenWhenPulseReturnedToOldValue() {
        TestWorld world = new TestWorld();
        BlockState comparator = state("comparator")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.MODE, ComparatorMode.COMPARE)
                .with(Properties.POWERED, false);

        comparator.getBlock().scheduledTick(world, 0, 64, 0, comparator);

        assertEquals(1, world.neighborUpdates);
    }

    @Test
    void subtractTickSuppressesUnchangedOutputUpdate() {
        TestWorld world = new TestWorld();
        BlockState comparator = state("comparator")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.MODE, ComparatorMode.SUBTRACT)
                .with(Properties.POWERED, false);

        comparator.getBlock().scheduledTick(world, 0, 64, 0, comparator);

        assertEquals(0, world.neighborUpdates);
    }

    @Test
    void scheduledTickStoresOutputInBlockEntityAndPowersState() {
        TestWorld world = new TestWorld();
        BlockState comparator = state("comparator")
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.MODE, ComparatorMode.COMPARE)
                .with(Properties.POWERED, false);
        world.put(0, 64, 0, comparator);
        world.put(-1, 64, 0, state("redstone_block"));

        comparator.getBlock().scheduledTick(world, 0, 64, 0, comparator);

        ComparatorBlockEntity blockEntity = (ComparatorBlockEntity) world.getBlockEntity(0, 64, 0);
        assertEquals(15, blockEntity.getOutputSignal());
        BlockState updated = Blocks.getState(world.getBlock(0, 64, 0));
        assertTrue(updated.get(Properties.POWERED));
        assertEquals(15, updated.getBlock().getWeakPower(
                world, 0, 64, 0, updated, Direction.EAST));
    }

    @Test
    void comparatorBlockEntityPersistsOutputSignal() {
        ComparatorBlockEntity source = new ComparatorBlockEntity(
                BlockEntities.COMPARATOR, new BlockPos(0, 64, 0));
        source.setOutputSignal(9);
        DataTag tag = new DataTag();
        source.save(tag);

        ComparatorBlockEntity restored = new ComparatorBlockEntity(
                BlockEntities.COMPARATOR, new BlockPos(0, 64, 0));
        restored.load(tag);

        assertEquals(9, restored.getOutputSignal());
    }

    @Test
    void legacyPowerPropertyMigratesToPoweredVisualState() {
        BlockState migrated = BlockStateCodec.decode(
                "skyengine:comparator[facing=east,mode=compare,power=7]");

        assertTrue(migrated.get(Properties.POWERED));
        assertFalse(migrated.getValues().containsKey(Properties.POWER));
    }

    @Test
    void comparatorBlockEntityIsNonTickingButPistonStillDestroysBlock() {
        assertFalse(BlockEntities.COMPARATOR.isTicking());
        assertEquals(PistonReaction.DESTROY, state("comparator").getBlock().getPistonReaction());
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(16);
        private final Map<Long, BlockEntity> blockEntities = new HashMap<>();
        private int itemFrameSignal = -1;
        private TickPriority scheduledPriority;
        private int neighborUpdates;

        TestWorld() {
            super("__comparator_test", level(), null, null);
        }

        void put(int x, int y, int z, BlockState state) {
            long position = BlockPos.asLong(x, y, z);
            this.blocks.put(position, state.getId());
            if (state.getBlock().getBlockEntityType() == BlockEntities.COMPARATOR
                    && !this.blockEntities.containsKey(position)) {
                ComparatorBlockEntity blockEntity = new ComparatorBlockEntity(
                        BlockEntities.COMPARATOR, new BlockPos(x, y, z));
                blockEntity.setWorld(this);
                this.blockEntities.put(position, blockEntity);
            }
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
            this.blocks.put(BlockPos.asLong(x, y, z), block);
            return true;
        }

        @Override
        public int getItemFrameAnalogSignal(int x, int y, int z, Direction direction) {
            return this.itemFrameSignal;
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delayTicks, TickPriority priority) {
            this.scheduledPriority = priority;
        }

        @Override
        public void updateNeighbors(int x, int y, int z) {
            this.neighborUpdates++;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "comparator-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
