package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.ObserverBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/**
 * Beobachter: {@code facing} (6, Gesicht zum beobachteten Block) × {@code powered}
 * = 12 States. Voller opaker Würfel; die Puls-Logik lebt im {@link ObserverBehavior}.
 */
public final class ObserverArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING_ALL)
                .property(Properties.POWERED)
                .defaultValue(Properties.FACING_ALL, Direction.NORTH)
                .redstoneConductor(state -> false)
                .behavior(new ObserverBehavior());
    }
}
