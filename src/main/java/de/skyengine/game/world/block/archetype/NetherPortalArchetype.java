package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.NetherPortalBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/** Zustands- und Kollisionsregeln der duennen Portaloberflaeche. */
public final class NetherPortalArchetype implements Archetype {

    private static final ShapeProvider SHAPES = new ShapeProvider() {
        @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }

        @Override public BlockShape outline(BlockState state) {
            return state.get(Properties.HORIZONTAL_AXIS) == Direction.Axis.X
                    ? BlockShape.box(0, 0, 6.0 / 16.0, 1, 1, 10.0 / 16.0)
                    : BlockShape.box(6.0 / 16.0, 0, 0, 10.0 / 16.0, 1, 1);
        }
    };

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.HORIZONTAL_AXIS)
                .defaultValue(Properties.HORIZONTAL_AXIS, Direction.Axis.X)
                .shapes(SHAPES)
                .opaque(state -> false)
                .behavior(new NetherPortalBehavior());
    }
}
