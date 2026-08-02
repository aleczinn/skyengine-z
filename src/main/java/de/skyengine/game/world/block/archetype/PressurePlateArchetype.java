package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.PressurePlateBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/**
 * Druckplatte: nur {@code powered} — 2 States. Keine Kollision (man steht auf dem Block darunter),
 * Umriss aus dem Modell, damit gedrückt und ungedrückt automatisch stimmen.
 *
 * <p>Der Träger kommt über {@code "place_on_full_top": true} in der Block-JSON, dieselbe Regel
 * wie bei der Tür — das erledigt das generische {@code SupportBehavior}.
 */
public final class PressurePlateArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.POWERED)
                .behavior(new PressurePlateBehavior(def.press_ticks))
                .shapes(Shapes.outlineOnly())
                .opaque(state -> false);
    }
}
