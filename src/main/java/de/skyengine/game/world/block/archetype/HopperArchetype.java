package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.HopperBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/**
 * Trichter: {@code facing} (6, Auslaufrichtung, Default DOWN — UP ist tot, wie die
 * ceiling-Variante der Fackel) × {@code enabled} = 12 States. Die Kollisionsboxen
 * (Schale + Mitteltrichter) stehen als {@code collision} in der Block-JSON; Transfer-Logik
 * in der {@code HopperBlockEntity}, Redstone-Deaktivierung im {@link HopperBehavior}.
 */
public final class HopperArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING_ALL)
                .property(Properties.ENABLED)
                .defaultValue(Properties.FACING_ALL, Direction.DOWN)
                .defaultValue(Properties.ENABLED, true)
                .behavior(new HopperBehavior());
    }
}
