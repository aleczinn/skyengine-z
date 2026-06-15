package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Fluid (Wasser/Lava): LEVEL-Property (0..15), nie opak, keine Kollision. Optik kommt aus
 * dem Blockstate/Modell; animierte Textur über das Sprite-System. solid/layer setzt die JSON.
 */
public final class FluidArchetype implements Archetype {

    private static final ShapeProvider NO_COLLISION = new ShapeProvider() {
        @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
        @Override public BlockShape outline(BlockState state) { return BlockShape.FULL_CUBE; }
    };

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.LEVEL)
                .shapes(NO_COLLISION)
                .opaque(state -> false);
    }
}
