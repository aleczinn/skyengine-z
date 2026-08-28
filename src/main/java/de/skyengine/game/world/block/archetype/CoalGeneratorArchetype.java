package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.CoalGeneratorBehavior;
import de.skyengine.game.world.block.behavior.HorizontalFacingBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

public final class CoalGeneratorArchetype implements Archetype {
    @Override public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.LIT)
                .behavior(new HorizontalFacingBehavior())
                .behavior(new CoalGeneratorBehavior());
    }
}
