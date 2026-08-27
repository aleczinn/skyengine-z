package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FurnaceBlockEntityTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test void coalSmeltsCobblestoneAndKeepsRemainingBurnTime() {
        TestWorld world = new TestWorld();
        FurnaceBlockEntity furnace = world.furnace;
        furnace.getInventory().set(FurnaceBlockEntity.INPUT, stack("cobblestone", 1));
        furnace.getInventory().set(FurnaceBlockEntity.FUEL, stack("coal", 1));

        for (int i = 0; i < 200; i++) furnace.tick();

        assertEquals(item("stone"), furnace.getInventory().get(FurnaceBlockEntity.OUTPUT).getItem());
        assertTrue(furnace.getInventory().get(FurnaceBlockEntity.INPUT).isEmpty());
        assertTrue(furnace.getBurnTime() > 1300);
        assertTrue(world.state.get(de.skyengine.game.world.block.state.Properties.LIT));
    }

    @Test void sidedCapabilitiesRejectWrongInsertionAndOutputInsertion() {
        TestWorld world = new TestWorld();
        FurnaceBlockEntity furnace = world.furnace;
        ItemStorage top = furnace.getCapability(Capabilities.ITEM_STORAGE, Direction.UP).orElseThrow();
        ItemStorage side = furnace.getCapability(Capabilities.ITEM_STORAGE, Direction.NORTH).orElseThrow();
        ItemStorage bottom = furnace.getCapability(Capabilities.ITEM_STORAGE, Direction.DOWN).orElseThrow();

        assertTrue(top.insert(stack("coal", 1)).getCount() == 1);
        assertTrue(side.insert(stack("cobblestone", 1)).getCount() == 1);
        assertTrue(bottom.insert(stack("stone", 1)).getCount() == 1);
        assertTrue(side.insert(stack("coal", 1)).isEmpty());
        assertFalse(furnace.getInventory().get(FurnaceBlockEntity.FUEL).isEmpty());
    }

    @Test void inventoryAndProgressPersist() {
        FurnaceBlockEntity original = new FurnaceBlockEntity(BlockEntities.FURNACE, new BlockPos(0, 64, 0));
        original.getInventory().set(FurnaceBlockEntity.INPUT, stack("sand", 2));
        DataTag tag = new DataTag();
        original.save(tag);
        FurnaceBlockEntity loaded = new FurnaceBlockEntity(BlockEntities.FURNACE, new BlockPos(0, 64, 0));
        loaded.load(tag);
        assertEquals(2, loaded.getInventory().get(FurnaceBlockEntity.INPUT).getCount());
    }

    private static Item item(String id) { return Items.get(Identifier.of("skyengine:" + id)); }
    private static ItemStack stack(String id, int count) { return new ItemStack(item(id), count); }

    private static final class TestWorld extends Dimension {
        private BlockState state = BlockRegistry.get(Identifier.of("skyengine:furnace")).getDefaultState();
        private final FurnaceBlockEntity furnace;
        TestWorld() {
            super("__furnace_test", level(), null, null);
            this.furnace = new FurnaceBlockEntity(BlockEntities.FURNACE, new BlockPos(0, 64, 0));
            this.furnace.setWorld(this);
        }
        @Override public int getBlock(int x, int y, int z) { return this.state.getId(); }
        @Override public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            this.state = Blocks.getState(block); return true;
        }
        @Override public void markChunkModified(int x, int z) { }
        @Override public void updateComparatorOutputs(int x, int y, int z) { }
        private static LevelData level() {
            LevelData level = new LevelData(); level.name = "furnace-test"; level.seed = 1; level.worldType = "imported";
            return level;
        }
    }
}
