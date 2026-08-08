package de.skyengine.game.world.block.entity;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DispenserBlockEntityTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void reservoirSelectionNeverChoosesEmptySlotAndIsUniform() {
        TestWorld world = new TestWorld();
        DispenserBlockEntity dispenser = world.addDispenser(0, "dispenser");
        dispenser.getInventory().set(1, stone(1));
        dispenser.getInventory().set(4, stone(1));
        dispenser.getInventory().set(8, stone(1));
        int[] counts = new int[9];
        world.random().setSeed(12345L);

        for (int i = 0; i < 9_000; i++) counts[dispenser.randomSlot()]++;

        assertEquals(9_000, counts[1] + counts[4] + counts[8]);
        for (int slot : new int[] {1, 4, 8}) {
            assertTrue(counts[slot] > 2_800 && counts[slot] < 3_200,
                    "belegte Slots müssen gleichverteilt sein: Slot " + slot + " = " + counts[slot]);
        }
    }

    @Test
    void dropperInsertsOneItemIntoFacingContainer() {
        TestWorld world = new TestWorld();
        DispenserBlockEntity dropper = world.addDispenser(0, "dropper");
        ChestBlockEntity target = world.addChest(1);
        dropper.getInventory().set(0, stone(2));

        dropper.activate(Direction.EAST, true);

        assertEquals(1, dropper.getInventory().get(0).getCount());
        assertEquals(1, target.getInventory().get(0).getCount());
        assertTrue(world.spawned.isEmpty());
    }

    @Test
    void fullDropperTargetKeepsItemInsteadOfEjecting() {
        TestWorld world = new TestWorld();
        DispenserBlockEntity dropper = world.addDispenser(0, "dropper");
        ChestBlockEntity target = world.addChest(1);
        for (int slot = 0; slot < target.getInventory().size(); slot++) {
            target.getInventory().set(slot, stone(64));
        }
        dropper.getInventory().set(0, stone(1));

        dropper.activate(Direction.EAST, true);

        assertEquals(1, dropper.getInventory().get(0).getCount());
        assertTrue(world.spawned.isEmpty());
    }

    @Test
    void dropperWithoutContainerEjectsOneItemInFacingDirection() {
        TestWorld world = new TestWorld();
        DispenserBlockEntity dropper = world.addDispenser(0, "dropper");
        dropper.getInventory().set(0, stone(2));

        dropper.activate(Direction.EAST, true);

        assertEquals(1, dropper.getInventory().get(0).getCount());
        assertEquals(1, world.spawned.size());
        Entity entity = world.spawned.getFirst();
        assertInstanceOf(de.skyengine.game.entity.ItemEntity.class, entity);
        assertTrue(entity.motionX > 0, "Auswurf nach Osten braucht positiven X-Impuls");
    }

    @Test
    void signalAboveUsesQuasiConnectivityAndSchedulesFourTicksOnce() {
        TestWorld world = new TestWorld();
        BlockState dispenser = state("dispenser")
                .with(Properties.FACING_ALL, Direction.NORTH)
                .with(Properties.TRIGGERED, false);
        world.put(0, 64, dispenser);
        world.put(-1, 65, state("redstone_block"));

        BlockState triggered = dispenser.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, dispenser);
        BlockState unchanged = triggered.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, triggered);

        assertTrue(triggered.get(Properties.TRIGGERED));
        assertEquals(triggered, unchanged);
        assertEquals(1, world.scheduledTicks);
        assertEquals(4, world.lastDelay);
    }

    @Test
    void inventorySurvivesBlockEntityRoundTrip() {
        TestWorld world = new TestWorld();
        DispenserBlockEntity source = world.addDispenser(0, "dispenser");
        source.getInventory().set(3, stone(17));
        DataTag tag = new DataTag();
        source.save(tag);
        DispenserBlockEntity restored = new DispenserBlockEntity(BlockEntities.DISPENSER,
                new BlockPos(0, 64, 0));

        restored.load(tag);

        assertFalse(restored.getInventory().get(3).isEmpty());
        assertEquals(17, restored.getInventory().get(3).getCount());
    }

    private static ItemStack stone(int count) {
        return new ItemStack(Items.get(Identifier.of("skyengine:stone")), count);
    }

    private static BlockState state(String path) {
        return BlockRegistry.get(Identifier.of("skyengine:" + path)).getDefaultState();
    }

    private static final class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(16);
        private final Map<Long, BlockEntity> blockEntities = new HashMap<>();
        private final List<Entity> spawned = new ArrayList<>();
        private int scheduledTicks;
        private int lastDelay;

        TestWorld() {
            super("__dispenser_test", level(), null, null);
        }

        DispenserBlockEntity addDispenser(int x, String blockName) {
            BlockState state = state(blockName)
                    .with(Properties.FACING_ALL, Direction.EAST)
                    .with(Properties.TRIGGERED, false);
            this.put(x, 64, state);
            BlockEntityType<DispenserBlockEntity> type = blockName.equals("dropper")
                    ? BlockEntities.DROPPER : BlockEntities.DISPENSER;
            DispenserBlockEntity dispenser = new DispenserBlockEntity(type, new BlockPos(x, 64, 0));
            dispenser.setWorld(this);
            this.blockEntities.put(BlockPos.asLong(x, 64, 0), dispenser);
            return dispenser;
        }

        ChestBlockEntity addChest(int x) {
            this.put(x, 64, state("chest"));
            ChestBlockEntity chest = new ChestBlockEntity(BlockEntities.CHEST, new BlockPos(x, 64, 0));
            chest.setWorld(this);
            this.blockEntities.put(BlockPos.asLong(x, 64, 0), chest);
            return chest;
        }

        void put(int x, int y, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, 0), state.getId());
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
        public void spawnEntity(Entity entity) {
            this.spawned.add(entity);
        }

        @Override
        public void markChunkModified(int x, int z) {
        }

        @Override
        public void updateComparatorOutputs(int x, int y, int z) {
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delayTicks) {
            this.scheduledTicks++;
            this.lastDelay = delayTicks;
        }

        @Override
        public void playDispenserSuccess(int x, int y, int z) {
        }

        @Override
        public void playDispenserFailure(int x, int y, int z) {
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "dispenser-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
