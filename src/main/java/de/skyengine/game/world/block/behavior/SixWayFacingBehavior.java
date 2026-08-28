package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/** Places a six-way machine with its front facing the player. */
public final class SixWayFacingBehavior implements BlockBehavior {
    @Override public BlockState onPlace(PlacementContext ctx, BlockState state) {
        Direction facing;
        if (ctx.playerPitch() > 45) facing = Direction.UP;
        else if (ctx.playerPitch() < -45) facing = Direction.DOWN;
        else facing = Direction.fromYaw(ctx.playerYaw()).opposite();
        return state.with(Properties.FACING_ALL, facing);
    }
}
