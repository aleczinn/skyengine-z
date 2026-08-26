package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StructureWandItemTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void debugAxeUsesWoodenAxeVisualButRemainsCommandOnly() {
        Identifier id = Identifier.of("skyengine:structure_wand");
        Item item = Items.get(id);
        assertNotNull(item);
        assertEquals("game/textures/item/wooden_axe.png", item.getIconTexture());
        assertEquals(1, item.getMaxStackSize());
        assertTrue(Items.isCommandOnly(id));
    }
}
