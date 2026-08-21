package de.skyengine.game;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameContainerUnderwaterEffectTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void debugFlagOnlyGatesTheVisualWaterEffect() {
        assertTrue(GameContainer.shouldRenderUnderwaterEffect(true, Blocks.getState(Blocks.WATER)));
        assertFalse(GameContainer.shouldRenderUnderwaterEffect(false, Blocks.getState(Blocks.WATER)));
        assertFalse(GameContainer.shouldRenderUnderwaterEffect(true, Blocks.getState(Blocks.LAVA)));
        assertFalse(GameContainer.shouldRenderUnderwaterEffect(true, null));
    }
}
