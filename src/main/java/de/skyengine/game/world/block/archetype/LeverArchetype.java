package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.AttachBehavior;
import de.skyengine.game.world.block.behavior.LeverBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.Properties;

import java.util.EnumSet;

/**
 * Hebel: wie der Knopf an allen drei Flächen anbringbar ({@code face} × {@code facing} ×
 * {@code powered} = 24 States), aber ohne Selbst-Rücksetzung — {@link LeverBehavior}
 * schaltet nur um.
 */
public final class LeverArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.ATTACH)
                .property(Properties.FACING)
                .property(Properties.POWERED)
                .behavior(new AttachBehavior(EnumSet.allOf(AttachFace.class)))
                .behavior(new LeverBehavior())
                /* Feste MC-Sockel-Boxen statt outlineOnly(): die modellabgeleitete Box
                   wäre durch den 45-Grad-Griff aufgebläht. */
                .shapes(Shapes.lever())
                .opaque(state -> false);
    }
}
