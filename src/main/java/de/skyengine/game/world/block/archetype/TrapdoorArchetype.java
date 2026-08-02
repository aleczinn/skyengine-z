package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.TrapdoorBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/**
 * Falltür: FACING/HALF/OPEN — im Unterschied zur Tür EINTEILIG (kein {@code parts}) und ohne
 * HINGE, also 16 statt 32 States. Klappen und Ausrichtung über {@link TrapdoorBehavior},
 * zustandsabhängige Kollision über {@link Shapes#trapdoor()}, nie opak.
 */
public final class TrapdoorArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.HALF)
                .property(Properties.OPEN)
                .behavior(new TrapdoorBehavior(def.hand_openable))
                .shapes(Shapes.trapdoor())
                .opaque(state -> false);
    }
}
