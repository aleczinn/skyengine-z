package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.EnergyCubeBehavior;
import de.skyengine.game.world.block.behavior.SixWayFacingBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

public final class EnergyCubeArchetype implements Archetype {
    @Override public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING_ALL)
                .defaultValue(Properties.FACING_ALL, Direction.NORTH)
                .behavior(new SixWayFacingBehavior())
                .behavior(new EnergyCubeBehavior());
    }
}
