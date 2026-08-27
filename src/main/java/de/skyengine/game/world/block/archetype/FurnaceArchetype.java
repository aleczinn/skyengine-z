package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.FurnaceBehavior;
import de.skyengine.game.world.block.behavior.HorizontalFacingBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/** Ofen: horizontale Ausrichtung, Brennzustand und Inventar-Drop-Verhalten. */
public final class FurnaceArchetype implements Archetype {
    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.LIT)
                .behavior(new HorizontalFacingBehavior())
                .behavior(new FurnaceBehavior());
    }
}
