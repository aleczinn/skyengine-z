package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
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
        original.setSideMode(RelativeSide.RIGHT, EnergySideMode.DISABLED);
        DataTag portable = new DataTag();
        original.savePortable(portable);

        EnergyCubeBlockEntity loaded = cube();
        loaded.loadPortable(portable);
        assertEquals(1_600L, loaded.getEnergy()); // one capability operation is rate limited
        assertEquals(EnergySideMode.DISABLED, loaded.getSideMode(RelativeSide.RIGHT));
        assertTrue(loaded.getCapability(Capabilities.ENERGY, Direction.EAST).isEmpty());
    }

    @Test void portableItemDataRoundTripsAndCubeIsUnstackable() {
        ItemStack stack = new ItemStack(Items.get(Identifier.of("voxelstories:basic_energy_cube")), 1);
        stack.setCustomData(new DataTag().putLong("energy", 42_000));
        ItemStack loaded = ItemStack.load(stack.save());
        assertEquals(1, loaded.getMaxStackSize());
        assertEquals(42_000, loaded.getCustomData().getLong("energy", 0));
    }

    private static EnergyCubeBlockEntity cube() {
        return new EnergyCubeBlockEntity(BlockEntities.BASIC_ENERGY_CUBE, new BlockPos(0, 64, 0));
    }
}
