package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
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
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static BlockState extendedStickyPiston(TestWorld world) {
        BlockState piston = state("sticky_piston")
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, true);
        world.put(0, 64, 0, piston);
        return piston;
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends World {
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
            int x = 2, y = 64, z = 0;
            this.put(x, y, z, state("moving_piston"));
            PistonMovingBlockEntity cargo = new PistonMovingBlockEntity(
                    BlockEntities.PISTON_MOVING, new BlockPos(x, y, z));
            cargo.setWorld(this);
            cargo.configure(state("stone").getId(), Direction.EAST, true, false, false);
            DataTag tag = new DataTag();
            cargo.save(tag);
            tag.putDouble("progress", 0.5);
            cargo.load(tag);
            this.blockEntities.put(BlockPos.asLong(x, y, z), cargo);
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
            level.worldType = "imported";
            return level;
        }
    }
}
