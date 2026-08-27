package de.skyengine.game.world.block.model;

import de.skyengine.audio.BlockSoundGroup;
import de.skyengine.core.i18n.I18n;
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
                BlockTextures.layerOf("game/textures/block/white_bed_head_up.png"), 0F, 1F));
        assertTrue(hasTextureInZRange(inventory.quads(),
                BlockTextures.layerOf("game/textures/block/white_bed_foot_up.png"), 1F, 2F));
        assertTrue(lowVerticesAreOnlyAtOuterEnds(inventory.quads()));
        assertEquals("block/white_bed_inventory_display", BlockStateModels.inventoryDisplayModel(bed));
        assertEquals("block/white_bed_inventory_display",
                BlockStateModels.inventoryDisplayOverrideModel(bed));
        ModelLoader.Display gui = ModelLoader.display(
                BlockStateModels.inventoryDisplayOverrideModel(bed), "gui");
        assertNotNull(gui);
        assertArrayEquals(new float[]{30F, 340F, 0F}, gui.rotation());
        assertArrayEquals(new float[]{2F, 3F, 0F}, gui.translation());
        assertArrayEquals(new float[]{0.5325F, 0.5325F, 0.5325F}, gui.scale());

        ModelLoader.Display firstPerson = ModelLoader.display(
                BlockStateModels.inventoryDisplayOverrideModel(bed), "firstperson_righthand");
        assertNotNull(firstPerson);
        assertArrayEquals(new float[]{30F, 340F, 0F}, firstPerson.rotation());
        assertArrayEquals(new float[]{0F, 3F, 0F}, firstPerson.translation());
        assertArrayEquals(new float[]{0.375F, 0.375F, 0.375F}, firstPerson.scale());

        ModelLoader.Display thirdPerson = ModelLoader.display(
                BlockStateModels.inventoryDisplayOverrideModel(bed), "thirdperson_righthand");
        assertNotNull(thirdPerson);
        assertArrayEquals(new float[]{30F, 340F, 0F}, thirdPerson.rotation());
        assertArrayEquals(new float[]{0F, 3F, -2F}, thirdPerson.translation());
        assertArrayEquals(new float[]{0.23F, 0.23F, 0.23F}, thirdPerson.scale());
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
    void regularBlocksAndFencesUseTheirVanillaGuiTransforms() {
        ModelLoader.Display cube = ModelLoader.display("block/oak_planks", "gui");
        assertNotNull(cube);
        assertArrayEquals(new float[]{30F, 225F, 0F}, cube.rotation());
        assertArrayEquals(new float[]{0F, 0F, 0F}, cube.translation());
        assertArrayEquals(new float[]{0.625F, 0.625F, 0.625F}, cube.scale());

        Block fence = block("oak_fence");
        String fenceModel = BlockStateModels.inventoryDisplayModel(fence);
        assertEquals("block/oak_fence_inventory", fenceModel);
        ModelLoader.Display fenceGui = ModelLoader.display(fenceModel, "gui");
        assertNotNull(fenceGui);
        assertArrayEquals(new float[]{30F, 135F, 0F}, fenceGui.rotation());
        assertArrayEquals(new float[]{0F, 0F, 0F}, fenceGui.translation());
        assertArrayEquals(new float[]{0.625F, 0.625F, 0.625F}, fenceGui.scale());
    }

    @Test
    void stairsOverrideTheCubeGuiRotationLikeMinecraft() {
        ModelLoader.Display stairs = ModelLoader.display("block/oak_stairs", "gui");

        assertNotNull(stairs);
        assertArrayEquals(new float[]{30F, 135F, 0F}, stairs.rotation());
        assertArrayEquals(new float[]{0F, 0F, 0F}, stairs.translation());
        assertArrayEquals(new float[]{0.625F, 0.625F, 0.625F}, stairs.scale());
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

        List<String> building = CreativeTabs.items("building_blocks").stream()
                .map(item -> item.getId().path()).toList();
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "pale_oak"};
        for (String wood : woods) {
            if (wood.equals("oak")) {
                assertOrdered(building, wood + "_log", wood + "_planks", wood + "_stairs", wood + "_slab",
                        wood + "_fence", wood + "_fence_gate", wood + "_door", wood + "_trapdoor",
                        wood + "_pressure_plate", wood + "_button");
            } else {
                assertOrdered(building, wood + "_log", "stripped_" + wood + "_log", wood + "_planks",
                        wood + "_stairs", wood + "_slab", wood + "_fence", wood + "_fence_gate",
                        wood + "_door", wood + "_trapdoor", wood + "_pressure_plate", wood + "_button");
            }
            assertEquals(BlockSoundGroup.WOOD, block(wood + "_button").getSoundGroup());
            assertEquals(BlockSoundGroup.WOOD, block(wood + "_pressure_plate").getSoundGroup());
        }

        assertTrue(redstone.indexOf("oak_button") < redstone.indexOf("stone_button"));
        assertTrue(redstone.indexOf("oak_pressure_plate") < redstone.indexOf("stone_pressure_plate"));
    }

    @Test
    void newWoodControlsHaveEnglishAndGermanNames() {
        I18n.load("en_us");
        assertEquals("Spruce Button", I18n.tr("block.voxelstories.spruce_button"));
        assertEquals("Pale Oak Pressure Plate", I18n.tr("block.voxelstories.pale_oak_pressure_plate"));
        I18n.load("de_de");
        assertEquals("Fichtenholzknopf", I18n.tr("block.voxelstories.spruce_button"));
        assertEquals("Blasseichenholzdruckplatte", I18n.tr("block.voxelstories.pale_oak_pressure_plate"));
        I18n.load("en_us");
    }

    @Test
    void emeraldHopperReplacesOldIdWithoutMigrationAlias() {
        assertNotNull(Registries.BLOCK.get(Identifier.of("voxelstories:emerald_hopper")));
        assertNotNull(Registries.ITEM.get(Identifier.of("voxelstories:emerald_hopper")));
        assertNull(Registries.BLOCK.get(Identifier.of("voxelstories:netherite_hopper")));
        assertNull(Registries.ITEM.get(Identifier.of("voxelstories:netherite_hopper")));
        assertNull(BlockStateCodec.decode("voxelstories:netherite_hopper[facing=down,enabled=true]"));
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
        return Registries.BLOCK.get(Identifier.of("voxelstories:" + path));
    }
}
