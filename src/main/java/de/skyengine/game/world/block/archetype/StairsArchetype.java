package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.StairsBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/**
 * Treppe: FACING/HALF/STAIR_SHAPE, Eckenformung via Verhalten, nie opak. Die Shape bleibt
 * vorerst modell-abgeleitet (Parität); per JSON-Override durch eine eigene ersetzbar.
 */
public final class StairsArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.HALF)
                .property(Properties.STAIR_SHAPE)
                .behavior(new StairsBehavior())
                .shapes(Shapes.modelDerived())
                .opaque(state -> false);
    }
}
