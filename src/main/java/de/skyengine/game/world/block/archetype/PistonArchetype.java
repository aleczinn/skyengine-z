package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.PistonBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/**
 * Kolben-Basis (normal/klebrig — zwei Registrierungen derselben Klasse):
 * {@code facing} (6 Richtungen) × {@code extended} = 12 States. Eingefahren ein opaker
 * Vollwürfel (Culling!), ausgefahren eine 12-px-Basis mit passender Kollision.
 */
public final class PistonArchetype implements Archetype {

    private final boolean sticky;

    public PistonArchetype(boolean sticky) {
        this.sticky = sticky;
    }

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING_ALL)
                .property(Properties.EXTENDED)
                .defaultValue(Properties.FACING_ALL, Direction.NORTH)
                .behavior(new PistonBehavior(this.sticky))
                .shapes(Shapes.piston())
                .opaque(state -> !state.get(Properties.EXTENDED));
    }
}
