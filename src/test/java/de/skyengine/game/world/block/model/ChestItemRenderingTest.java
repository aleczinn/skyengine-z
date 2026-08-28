package de.skyengine.game.world.block.model;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ChestItemRenderingTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void chestUsesMinecraft262SpecialItemTransforms() {
        Block chest = Registries.BLOCK.get(Identifier.of("voxelstories:chest"));
        assertNotNull(chest);
        assertEquals("block/chest_item", BlockStateModels.inventoryDisplayModel(chest));

        assertDisplay("gui",
                new float[]{30F, 45F, 0F}, new float[]{0F, 0F, 0F},
                new float[]{0.625F, 0.625F, 0.625F});
        assertDisplay("firstperson_righthand",
                new float[]{0F, 315F, 0F}, new float[]{0F, 0F, 0F},
                new float[]{0.4F, 0.4F, 0.4F});
        assertDisplay("thirdperson_righthand",
                new float[]{75F, 315F, 0F}, new float[]{0F, 2.5F, 0F},
                new float[]{0.375F, 0.375F, 0.375F});
    }

    private static void assertDisplay(String context, float[] rotation,
                                      float[] translation, float[] scale) {
        ModelLoader.Display display = ModelLoader.display("block/chest_item", context);
        assertNotNull(display);
        assertArrayEquals(rotation, display.rotation());
        assertArrayEquals(translation, display.translation());
        assertArrayEquals(scale, display.scale());
    }
}
