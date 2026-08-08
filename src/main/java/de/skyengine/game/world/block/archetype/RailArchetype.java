package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.RailBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RailShape;
import de.skyengine.game.world.block.state.Property;

/** Normale, Antriebs-, Sensor- und Aktivierungsschienen mit Vanilla-Stateaufbau. */
public final class RailArchetype implements Archetype {

    private static final BlockShape OUTLINE = BlockShape.box(0, 0, 0, 1, 2 / 16.0, 1);

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        RailBehavior.Kind kind = RailBehavior.Kind.byName(def.rail_kind);
        Property<RailShape> shape = kind.straightOnly()
                ? Properties.STRAIGHT_RAIL_SHAPE : Properties.RAIL_SHAPE;
        cfg.property(shape)
                .defaultValue(shape, RailShape.NORTH_SOUTH)
                .behavior(new RailBehavior(kind, shape))
                .shapes(new ShapeProvider() {
                    @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
                    @Override public BlockShape outline(BlockState state) { return OUTLINE; }
                })
                .opaque(state -> false)
                .redstoneConductor(state -> false);
        if (kind.hasPoweredState()) cfg.property(Properties.POWERED);
    }
}
