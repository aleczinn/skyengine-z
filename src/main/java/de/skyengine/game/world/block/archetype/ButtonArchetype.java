package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.AttachBehavior;
import de.skyengine.game.world.block.behavior.ButtonBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.Properties;

import java.util.EnumSet;

/**
 * Knopf: hängt wie die Fackel an einer Fläche, kann aber zusätzlich gedrückt werden —
 * {@code face} × {@code facing} × {@code powered} = 24 States.
 *
 * <p>Die Trägerlogik wird nicht nachgebaut, sondern durch Komposition wiederverwendet: das
 * {@link AttachBehavior} setzt Fläche und Wandrichtung und lässt den Knopf abfallen, wenn der
 * Träger verschwindet; das {@link ButtonBehavior} ergänzt nur das Drücken und die Ausrichtung am
 * Boden/an der Decke. Anders als die Fackel sind alle DREI Flächen erlaubt.
 */
public final class ButtonArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.ATTACH)
                .property(Properties.FACING)
                .property(Properties.POWERED)
                .behavior(new AttachBehavior(EnumSet.allOf(AttachFace.class)))
                .behavior(new ButtonBehavior(def.press_ticks))
                .shapes(Shapes.outlineOnly())
                .opaque(state -> false);
    }
}
