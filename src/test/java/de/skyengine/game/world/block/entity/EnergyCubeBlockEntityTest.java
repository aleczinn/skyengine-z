package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.item.CreativeTabs;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EnergyCubeBlockEntityTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test void defaultsToFrontOutputAndAllOtherSidesInput() {
        EnergyCubeBlockEntity cube = cube();
        EnergyStorage front = cube.getCapability(Capabilities.ENERGY, Direction.NORTH).orElseThrow();
        EnergyStorage side = cube.getCapability(Capabilities.ENERGY, Direction.EAST).orElseThrow();
        assertTrue(front.canExtract());
        assertFalse(front.canReceive());
        assertTrue(side.canReceive());
        assertFalse(side.canExtract());
    }

    @Test void sideModesAndLongEnergyRoundTripThroughPortableData() {
        EnergyCubeBlockEntity original = cube();
        original.getCapability(Capabilities.ENERGY, Direction.EAST).orElseThrow()
                .receive(1_234_567L, false);
        original.setSideMode(RelativeSide.LEFT, EnergySideMode.DISABLED);
        DataTag portable = new DataTag();
        original.savePortable(portable);

        EnergyCubeBlockEntity loaded = cube();
        loaded.loadPortable(portable);
        assertEquals(1_600L, loaded.getEnergy()); // one capability operation is rate limited
        assertEquals(EnergySideMode.DISABLED, loaded.getSideMode(RelativeSide.LEFT));
        assertTrue(loaded.getCapability(Capabilities.ENERGY, Direction.EAST).isEmpty());
    }

    @Test void portableItemDataRoundTripsAndCubeIsUnstackable() {
        ItemStack stack = new ItemStack(Items.get(Identifier.of("voxelstories:basic_energy_cube")), 1);
        stack.setCustomData(new DataTag().putLong("energy", 42_000));
        ItemStack loaded = ItemStack.load(stack.save());
        assertEquals(1, loaded.getMaxStackSize());
        assertEquals(42_000, loaded.getCustomData().getLong("energy", 0));
    }

    @Test void allInputAndOutputFacesShareOneTransferBudgetPerTick() {
        EnergyCubeBlockEntity cube = cube();
        EnergyStorage east = cube.getCapability(Capabilities.ENERGY, Direction.EAST).orElseThrow();
        EnergyStorage west = cube.getCapability(Capabilities.ENERGY, Direction.WEST).orElseThrow();
        assertEquals(1_000, east.receive(1_000, false));
        assertEquals(600, west.receive(1_000, false));
        assertEquals(1_600, cube.getEnergy());

        cube.setSideMode(RelativeSide.LEFT, EnergySideMode.OUTPUT);
        cube.tick();
        EnergyStorage north = cube.getCapability(Capabilities.ENERGY, Direction.NORTH).orElseThrow();
        EnergyStorage outputEast = cube.getCapability(Capabilities.ENERGY, Direction.EAST).orElseThrow();
        assertEquals(1_000, north.extract(1_000, false));
        assertEquals(600, outputEast.extract(1_000, false));
    }

    @Test void cubeItemsExposeStackBackedEnergyAndBothMachineSlotsTransfer() {
        ItemStack battery = new ItemStack(Items.get(Identifier.of("voxelstories:basic_energy_cube")), 1);
        EnergyStorage batteryEnergy = battery.getItem().getCapability(Capabilities.ENERGY, battery).orElseThrow();
        assertEquals(1_600, batteryEnergy.receive(2_000, false));
        assertEquals(1_600, battery.getCustomData().getLong("energy", 0));

        EnergyCubeBlockEntity cube = cube();
        cube.getInventory().set(0, battery);
        cube.tick();
        assertEquals(1_600, cube.getEnergy());
        assertEquals(0, batteryEnergy.getEnergy());

        ItemStack emptyBattery = new ItemStack(Items.get(Identifier.of("voxelstories:basic_energy_cube")), 1);
        cube.getInventory().set(0, ItemStack.EMPTY);
        cube.getInventory().set(1, emptyBattery);
        cube.tick();
        assertEquals(0, cube.getEnergy());
        assertEquals(1_600, EnergyCubeBlockEntity.itemEnergy(emptyBattery).getEnergy());
    }

    @Test void fullSaveKeepsSlotsButPortableDropDoesNotAndOldDataDefaultsToAutoEject() {
        EnergyCubeBlockEntity cube = cube();
        ItemStack battery = new ItemStack(Items.get(Identifier.of("voxelstories:basic_energy_cube")), 1);
        cube.getInventory().set(0, battery);
        cube.setAutoEject(false);
        DataTag full = new DataTag();
        cube.save(full);
        DataTag portable = new DataTag();
        cube.savePortable(portable);
        assertTrue(full.getTag("inventory") != null);
        assertTrue(portable.getTag("inventory") == null);

        EnergyCubeBlockEntity loaded = cube();
        loaded.load(full);
        assertFalse(loaded.isAutoEject());
        assertFalse(loaded.getInventory().get(0).isEmpty());
        EnergyCubeBlockEntity legacy = cube();
        legacy.load(new DataTag());
        assertTrue(legacy.isAutoEject());
    }

    @Test void relativeSidesAreAnExactBijectionForEveryPossibleFront() {
        for (Direction front : Direction.sharedValues()) {
            for (RelativeSide relative : RelativeSide.values()) {
                Direction world = relative.toWorld(front);
                assertEquals(relative, RelativeSide.fromWorld(front, world));
            }
        }
        assertEquals(Direction.EAST, RelativeSide.LEFT.toWorld(Direction.NORTH));
        assertEquals(Direction.WEST, RelativeSide.RIGHT.toWorld(Direction.NORTH));
        assertEquals(Direction.SOUTH, RelativeSide.TOP.toWorld(Direction.UP));
        assertEquals(Direction.NORTH, RelativeSide.BOTTOM.toWorld(Direction.UP));
    }

    @Test void technologyTabContainsOnlyTheThreeEnergyComponentsInRequestedOrder() {
        assertEquals(java.util.List.of("coal_generator", "basic_energy_cube", "basic_universal_cable"),
                CreativeTabs.items("technology").stream().map(item -> item.getId().path()).toList());
        assertTrue(CreativeTabs.items("functional").stream().noneMatch(item ->
                item.getId().path().equals("coal_generator")
                        || item.getId().path().equals("basic_energy_cube")
                        || item.getId().path().equals("basic_universal_cable")));
    }

    private static EnergyCubeBlockEntity cube() {
        return new EnergyCubeBlockEntity(BlockEntities.BASIC_ENERGY_CUBE, new BlockPos(0, 64, 0));
    }
}
