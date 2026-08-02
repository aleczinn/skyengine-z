package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/**
 * Kolben-Kopf (technischer Block, {@code no_item}): {@code facing} (6) × {@code type}
 * (normal/sticky) = 12 States. Existiert nur vor einer ausgefahrenen Basis — die
 * Konsistenz-Regeln (Selbstabbau ohne Basis, Basis-Abbau beim Kopf-Abbau) macht das
 * {@link de.skyengine.game.world.block.behavior.PistonHeadBehavior}.
 */
public final class PistonHeadArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING_ALL)
                .property(Properties.PISTON_TYPE)
                .defaultValue(Properties.FACING_ALL, Direction.NORTH)
                .behavior(new de.skyengine.game.world.block.behavior.PistonHeadBehavior())
                .shapes(Shapes.pistonHead())
                .opaque(state -> false);
    }
}
