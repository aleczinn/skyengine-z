package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.AttachBehavior;
import de.skyengine.game.world.block.behavior.RedstoneTorchBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.Properties;

import java.util.EnumSet;

/**
 * Redstone-Fackel: wie die Fackel an Boden oder Wand ({@code face} × {@code facing}),
 * zusätzlich LIT (Default AN — eine frisch gesetzte Fackel leuchtet, der erste geplante
 * Tick korrigiert sie ggf.). {@code light_level} der JSON wirkt über die LIT-Konvention
 * nur im leuchtenden Zustand.
 */
public final class RedstoneTorchArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.ATTACH)
                .property(Properties.FACING)
                .property(Properties.LIT)
                .defaultValue(Properties.LIT, true)
                .behavior(new AttachBehavior(EnumSet.of(AttachFace.FLOOR, AttachFace.WALL)))
                .behavior(new RedstoneTorchBehavior())
                .shapes(Shapes.attached())
                .opaque(state -> false);
    }
}
