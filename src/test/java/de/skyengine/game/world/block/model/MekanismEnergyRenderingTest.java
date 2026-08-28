package de.skyengine.game.world.block.model;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MekanismEnergyRenderingTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test void originalEnergyCubeContainsFrameLedsAndPortsForEverySide() {
        assertTrue(ModelLoader.bakeGroup("block/mekanism/energy_cube_basic", "frame").quads().length > 50);
        for (String side : new String[]{"front", "back", "left", "right", "top", "bottom"}) {
            assertTrue(ModelLoader.bakeGroup("block/mekanism/energy_cube_basic", side + "LEDs").quads().length > 0);
            assertTrue(ModelLoader.bakeGroup("block/mekanism/energy_cube_basic", side + "Port").quads().length > 0);
        }
    }

    @Test void upwardCableConnectionExtendsUpAndNeverDownIntoTheGround() {
        Block cable = BlockRegistry.get(Identifier.of("voxelstories:basic_universal_cable"));
        BlockState state = withBoolean(cable.getDefaultState(), "up", true);
        AABB[] boxes = cable.getCollisionShape(state).boxes();
        assertTrue(boxes.length >= 2);
        assertEquals(5 / 16D, minimum(boxes, true), 0.00001);
        assertEquals(1D, maximum(boxes, true), 0.00001);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withBoolean(BlockState state, String name, boolean value) {
        for (Property<?> property : state.getValues().keySet()) {
            if (property.getName().equals(name)) return state.with((Property) property, value);
        }
        throw new AssertionError("Missing property " + name);
    }

    private static double minimum(AABB[] boxes, boolean y) {
        double value = Double.POSITIVE_INFINITY;
        for (AABB box : boxes) value = Math.min(value, y ? box.minY : box.minX);
        return value;
    }

    private static double maximum(AABB[] boxes, boolean y) {
        double value = Double.NEGATIVE_INFINITY;
        for (AABB box : boxes) value = Math.max(value, y ? box.maxY : box.maxX);
        return value;
    }
}
