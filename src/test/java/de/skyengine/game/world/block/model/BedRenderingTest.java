package de.skyengine.game.world.block.model;

import de.skyengine.audio.BlockSoundGroup;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.item.CreativeTabs;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BedRenderingTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void inventoryModelContainsBothBlockHalves() {
        Block bed = block("white_bed");
        ModelLoader.Baked inventory = BlockStateModels.inventoryOverride(bed);

        assertNotNull(inventory);
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (BakedQuad quad : inventory.quads()) {
            for (int i = 2; i < quad.vertices().length; i += 5) {
                minZ = Math.min(minZ, quad.vertices()[i]);
                maxZ = Math.max(maxZ, quad.vertices()[i]);
            }
        }
        assertEquals(0F, minZ, 0.0001F);
        assertEquals(2F, maxZ, 0.0001F);
        assertTrue(hasTextureInZRange(inventory.quads(),
                BlockTextures.layerOf("game/textures/block/white_bed_head_up.png"), 1F, 2F));
        assertTrue(hasTextureInZRange(inventory.quads(),
                BlockTextures.layerOf("game/textures/block/white_bed_foot_up.png"), 0F, 1F));
        assertTrue(lowVerticesAreOnlyAtOuterEnds(inventory.quads()));
        assertEquals("block/white_bed_inventory_display", BlockStateModels.inventoryDisplayModel(bed));
        assertEquals("block/white_bed_inventory_display",
                BlockStateModels.inventoryDisplayOverrideModel(bed));
        ModelLoader.Display gui = ModelLoader.display(
                BlockStateModels.inventoryDisplayOverrideModel(bed), "gui");
        assertNotNull(gui);
        assertArrayEquals(new float[]{30F, 160F, 0F}, gui.rotation());
        assertArrayEquals(new float[]{1F, 0F, 0F}, gui.translation());
        assertArrayEquals(new float[]{0.5325F, 0.5325F, 0.5325F}, gui.scale());
        assertEquals(BlockSoundGroup.WOOD, bed.getSoundGroup());
    }

    @Test
    void fenceGateUsesItsVanillaGuiTransformInsteadOfCubeRotation() {
        Block gate = block("oak_fence_gate");
        String displayModel = BlockStateModels.inventoryDisplayOverrideModel(gate);

        assertEquals("block/oak_fence_gate", displayModel);
        ModelLoader.Display gui = ModelLoader.display(displayModel, "gui");
        assertNotNull(gui);
        assertArrayEquals(new float[]{30F, 45F, 0F}, gui.rotation());
        assertArrayEquals(new float[]{0F, -1F, 0F}, gui.translation());
        assertArrayEquals(new float[]{0.8F, 0.8F, 0.8F}, gui.scale());
    }

    @Test
    void collisionUsesMattressAndTwoOuterFeetPerHalf() {
        Block bed = block("white_bed");
        BlockState foot = withPart(bed.getDefaultState().with(Properties.FACING, Direction.NORTH), "foot");
        BlockState head = withPart(bed.getDefaultState().with(Properties.FACING, Direction.NORTH), "head");

        assertBedHalfShape(foot.getBlock().getCollisionShape(foot).boxes(), true);
        assertBedHalfShape(head.getBlock().getCollisionShape(head).boxes(), false);
    }

    @Test
    void northFacingPlacementPutsOpenEndsAndLegsOnOuterEdges() {
        Block bed = block("white_bed");
        BlockState head = withPart(bed.getDefaultState().with(Properties.FACING, Direction.NORTH), "head");
        BlockState foot = withPart(bed.getDefaultState().with(Properties.FACING, Direction.NORTH), "foot");

        assertTrue(hasCullFace(head.getModel(), Direction.SOUTH.faceIndex()));
        assertTrue(hasCullFace(foot.getModel(), Direction.NORTH.faceIndex()));
    }

    @Test
    void bedsAndRequestedComponentsHaveVanillaStyleCreativeOrder() {
        List<String> colored = CreativeTabs.items("colored_blocks").stream()
                .map(item -> item.getId().path()).toList();
        assertTrue(colored.contains("white_bed"));
        assertTrue(colored.indexOf("pink_bed") > colored.indexOf("white_bed"));

        List<String> redstone = CreativeTabs.items("redstone_blocks").stream()
                .map(item -> item.getId().path()).toList();
        assertOrdered(redstone, "piston", "sticky_piston", "slime_block", "honey_block",
                "dispenser", "dropper", "hopper", "emerald_hopper", "chest", "iron_door", "oak_door",
                "iron_trapdoor", "oak_trapdoor", "oak_fence_gate", "powered_rail");
        assertTrue(redstone.contains("redstone_ore"));
    }

    @Test
    void emeraldHopperReplacesOldIdWithoutMigrationAlias() {
        assertNotNull(Registries.BLOCK.get(Identifier.of("skyengine:emerald_hopper")));
        assertNotNull(Registries.ITEM.get(Identifier.of("skyengine:emerald_hopper")));
        assertNull(Registries.BLOCK.get(Identifier.of("skyengine:netherite_hopper")));
        assertNull(Registries.ITEM.get(Identifier.of("skyengine:netherite_hopper")));
        assertNull(BlockStateCodec.decode("skyengine:netherite_hopper[facing=down,enabled=true]"));
    }

    private static boolean hasCullFace(BakedQuad[] quads, int face) {
        for (BakedQuad quad : quads) if (quad.cullFace() == face) return true;
        return false;
    }

    private static boolean hasTextureInZRange(BakedQuad[] quads, int layer, float min, float max) {
        for (BakedQuad quad : quads) {
            if (quad.textureLayer() != layer) continue;
            boolean inside = true;
            for (int i = 2; i < quad.vertices().length; i += 5) {
                float z = quad.vertices()[i];
                if (z < min - 0.0001F || z > max + 0.0001F) inside = false;
            }
            if (inside) return true;
        }
        return false;
    }

    private static boolean lowVerticesAreOnlyAtOuterEnds(BakedQuad[] quads) {
        boolean found = false;
        for (BakedQuad quad : quads) {
            float[] vertices = quad.vertices();
            for (int i = 0; i < vertices.length; i += 5) {
                if (vertices[i + 1] >= 0.18F) continue;
                found = true;
                float z = vertices[i + 2];
                if (z > 0.25F && z < 1.75F) return false;
            }
        }
        return found;
    }

    private static void assertBedHalfShape(AABB[] boxes, boolean feetAtNorth) {
        assertEquals(3, boxes.length);
        assertTrue(contains(boxes, 0.5, 0.3, 0.5), "Matratzenbox fehlt");
        assertTrue(!contains(boxes, 0.5, 0.1, 0.5), "Raum unter dem Bett muss frei sein");
        double footZ = feetAtNorth ? 0.05 : 0.95;
        assertTrue(contains(boxes, 0.05, 0.1, footZ), "linker Bettfuß fehlt");
        assertTrue(contains(boxes, 0.95, 0.1, footZ), "rechter Bettfuß fehlt");
    }

    private static boolean contains(AABB[] boxes, double x, double y, double z) {
        for (AABB box : boxes) {
            if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY
                    && z >= box.minZ && z <= box.maxZ) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static BlockState withPart(BlockState state, String value) {
        for (Property<?> property : state.getValues().keySet()) {
            if (!property.getName().equals("part")) continue;
            for (Object candidate : property.getValues()) {
                if (BlockStateCodec.valueString(candidate).equals(value)) {
                    return state.with((Property<Object>) property, candidate);
                }
            }
        }
        throw new AssertionError("part property fehlt");
    }

    private static void assertOrdered(List<String> values, String... expected) {
        int previous = -1;
        for (String value : expected) {
            int current = values.indexOf(value);
            assertTrue(current > previous, value + " steht nicht in der erwarteten Reihenfolge");
            previous = current;
        }
    }

    private static Block block(String path) {
        return Registries.BLOCK.get(Identifier.of("skyengine:" + path));
    }
}
