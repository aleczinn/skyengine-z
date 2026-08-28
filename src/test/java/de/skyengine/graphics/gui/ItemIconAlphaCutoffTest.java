package de.skyengine.graphics.gui;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.item.Item;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ItemIconAlphaCutoffTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void cutoutBedRejectsMipmappedFringePixels() {
        assertEquals(ItemIconRenderer.CUTOUT_ALPHA,
                ItemIconRenderer.alphaCutoffFor(item("black_bed")));
    }

    @Test
    void translucentBlockKeepsItsMaterialAlpha() {
        assertEquals(ItemIconRenderer.TRANSLUCENT_ALPHA,
                ItemIconRenderer.alphaCutoffFor(item("slime_block")));
    }

    private static Item item(String path) {
        Item item = Registries.ITEM.get(Identifier.of("voxelstories:" + path));
        assertNotNull(item);
        return item;
    }
}
