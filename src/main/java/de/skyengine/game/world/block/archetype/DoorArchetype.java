package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.DoorBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/**
 * Tür: FACING/HALF/OPEN/HINGE, Zwei-Block-Verhalten + Toggle über {@link DoorBehavior},
 * zustandsabhängige Kollision über {@link Shapes#door()}, nie opak. Optik kommt aus dem
 * Blockstate/Modell. Komplett über das bestehende Daten-/Komponentensystem.
 */
public final class DoorArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.HALF)
                .property(Properties.OPEN)
                .property(Properties.HINGE)
                .behavior(new DoorBehavior())
                .shapes(Shapes.door())
                .opaque(state -> false);
    }
}
