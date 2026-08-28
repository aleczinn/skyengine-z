package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ItemArchetypeTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void jsonArchetypesCreateSpecializedRuntimeItems() {
        assertInstanceOf(FoodItem.class, item("apple"));
        assertInstanceOf(ItemFrameItem.class, item("item_frame"));
        assertInstanceOf(FlintAndSteelItem.class, item("flint_and_steel"));
        assertInstanceOf(ToolItem.class, item("netherite_pickaxe"));
        assertInstanceOf(SimpleItem.class, item("iron_ingot"));
    }

    @Test
    void bucketJsonPreservesFluidAndVanillaStackLimits() {
        BucketItem empty = assertInstanceOf(BucketItem.class, item("bucket"));
        BucketItem water = assertInstanceOf(BucketItem.class, item("water_bucket"));

        assertTrue(empty.isEmpty());
        assertEquals(16, empty.getMaxStackSize());
        assertEquals(1, water.getMaxStackSize());
        assertEquals(Identifier.of("voxelstories:water"), water.getFluid().getIdentifier());
    }

    @Test
    void toolJsonResolvesTypeAndTier() {
        ToolItem tool = assertInstanceOf(ToolItem.class, item("golden_axe"));
        assertEquals(ToolType.AXE, tool.getType());
        assertEquals(ToolTier.GOLD, tool.getTier());
    }

    private static Item item(String path) {
        return Items.get(Identifier.of("voxelstories:" + path));
    }
}
