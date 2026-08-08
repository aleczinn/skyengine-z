package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.DispenserBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/** Dispenser/Dropper: sechs Ausrichtungen und Vanillas gespeichertes Trigger-Flag. */
public final class DispenserArchetype implements Archetype {

    private final boolean dropper;

    public DispenserArchetype(boolean dropper) {
        this.dropper = dropper;
    }

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING_ALL)
                .property(Properties.TRIGGERED)
                .defaultValue(Properties.FACING_ALL, Direction.NORTH)
                .behavior(new DispenserBehavior(this.dropper));
    }
}
