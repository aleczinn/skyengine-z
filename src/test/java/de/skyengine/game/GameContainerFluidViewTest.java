package de.skyengine.game;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameContainerFluidViewTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void respectsVisibleSurfaceOfFlowingWater() {
        BlockState flowing = Blocks.getState(Blocks.WATER).with(Properties.LEVEL, 4);
        double surface = 64 + FluidGeometry.fluidHeight(flowing);

        assertTrue(GameContainer.isCameraSubmerged(surface - 0.001, 64, flowing,
                Blocks.getState(Blocks.AIR)));
        assertFalse(GameContainer.isCameraSubmerged(surface + 0.001, 64, flowing,
                Blocks.getState(Blocks.AIR)));
    }

    @Test
    void sameFluidAboveMakesCellFull() {
        BlockState water = Blocks.getState(Blocks.WATER);

        assertTrue(GameContainer.isCameraSubmerged(64.999, 64, water, water));
        assertFalse(GameContainer.isCameraSubmerged(65.0, 64, water, water));
    }

    @Test
    void rejectsNonFluidCells() {
        assertFalse(GameContainer.isCameraSubmerged(64.2, 64,
                Blocks.getState(Blocks.AIR), Blocks.getState(Blocks.AIR)));
    }
}
