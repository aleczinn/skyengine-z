package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/** Setzt die Achse (X/Y/Z) eines Pillar/Log aus der getroffenen Fläche. */
public final class PillarPlacementBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        Direction.Axis axis = ctx.faceY() != 0 ? Direction.Axis.Y
                : ctx.faceX() != 0 ? Direction.Axis.X : Direction.Axis.Z;
        return state.with(Properties.AXIS, axis);
    }
}
