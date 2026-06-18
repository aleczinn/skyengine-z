package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.connection.ConnectionBehavior;
import de.skyengine.game.world.block.connection.ConnectionComponent;
import de.skyengine.game.world.block.connection.ConnectionRules;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.model.ModelElements;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/**
 * Dünne Scheibe (Glass-Pane, Iron-Bars): 2px-Pfosten + Arme (Optik via Blockstate),
 * horizontale Verbindungen, Kollision 1.0 hoch.
 */
public final class PaneArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        double min = def.post != null ? def.post.x0() : ModelElements.px(7);
        double max = def.post != null ? def.post.x1() : ModelElements.px(9);

        cfg.property(Properties.NORTH).property(Properties.EAST)
                .property(Properties.SOUTH).property(Properties.WEST)
                .behavior(new ConnectionBehavior(new ConnectionComponent(
                        Direction.horizontal(), ConnectionRules.SAME_GROUP_OR_SOLID)))
                .shapes(Shapes.connected(min, max, 1.0))
                .opaque(state -> false)
                .connectionGroup("pane");
    }
}
