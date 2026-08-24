package de.skyengine.game.world.block.model;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DispenserRenderingTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void verticalDispenserAndDropperUseVanillaFaceTextures() {
        assertVerticalTextures("dispenser", "dispenser_front_vertical.png");
        assertVerticalTextures("dropper", "dropper_front_vertical.png");
    }

    private static void assertVerticalTextures(String blockName, String frontTexture) {
        Block block = BlockRegistry.get(Identifier.of("skyengine:" + blockName));
        int frontLayer = BlockTextures.layerOf("game/textures/block/" + frontTexture);
        int casingLayer = BlockTextures.layerOf("game/textures/block/furnace_top.png");

        for (Direction facing : new Direction[]{Direction.UP, Direction.DOWN}) {
            BlockState state = block.getDefaultState()
                    .with(Properties.FACING_ALL, facing)
                    .with(Properties.TRIGGERED, false);
            BakedQuad[] quads = state.getModel();

            assertEquals(6, quads.length, blockName + "-Quads fuer " + facing);
            for (Direction side : Direction.sharedValues()) {
                BakedQuad quad = quadOnFace(quads, side);
                int expected = side == facing ? frontLayer : casingLayer;
                assertEquals(expected, quad.textureLayer(),
                        blockName + "-Textur auf " + side + " bei facing=" + facing);
            }
        }
    }

    private static BakedQuad quadOnFace(BakedQuad[] quads, Direction side) {
        for (BakedQuad quad : quads) {
            if (quad.face() == side.faceIndex()) return quad;
        }
        throw new AssertionError("Keine Modellflaeche fuer " + side);
    }
}
