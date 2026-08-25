package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.StairShape;

/**
 * Treppen-Verhalten: Ausrichtung/Hälfte beim Platzieren und automatische Innen-/
 * Außen-Eckenformung (portierter Minecraft-Algorithmus). Ein Block gilt als Treppe,
 * wenn sein State die {@link Properties#STAIR_SHAPE}-Property trägt — kein instanceof,
 * damit das Verhalten archetyp-/modding-fähig bleibt.
 */
public final class StairsBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        Direction facing = Direction.fromYaw(ctx.playerYaw());
        BlockHalf half = (ctx.faceY() < 0 || (ctx.faceY() == 0 && ctx.hitY() > 0.5))
                ? BlockHalf.TOP : BlockHalf.BOTTOM;

        state = state.with(Properties.FACING, facing)
                .with(Properties.HALF, half)
                .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
        return state.with(Properties.STAIR_SHAPE, stairShape(ctx.world(), ctx.x(), ctx.y(), ctx.z(), state));
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        return state.with(Properties.STAIR_SHAPE, stairShape(world, x, y, z, state));
    }

    private static StairShape stairShape(Dimension world, int x, int y, int z, BlockState state) {
        Direction facing = state.get(Properties.FACING);
        BlockHalf half = state.get(Properties.HALF);

        BlockState front = stairAt(world, x + facing.offsetX(), y + facing.offsetY(), z + facing.offsetZ());
        if (front != null && front.get(Properties.HALF) == half) {
            Direction f = front.get(Properties.FACING);
            if (f.axis() != facing.axis() && isDifferent(world, x, y, z, state, f.opposite())) {
                return f == facing.rotateYCCW() ? StairShape.OUTER_LEFT : StairShape.OUTER_RIGHT;
            }
        }

        Direction back = facing.opposite();
        BlockState rear = stairAt(world, x + back.offsetX(), y + back.offsetY(), z + back.offsetZ());
        if (rear != null && rear.get(Properties.HALF) == half) {
            Direction f = rear.get(Properties.FACING);
            if (f.axis() != facing.axis() && isDifferent(world, x, y, z, state, f)) {
                return f == facing.rotateYCCW() ? StairShape.INNER_LEFT : StairShape.INNER_RIGHT;
            }
        }
        return StairShape.STRAIGHT;
    }

    /** true, wenn in Richtung dir KEINE gleich orientierte Treppe steht. */
    private static boolean isDifferent(Dimension world, int x, int y, int z, BlockState state, Direction dir) {
        BlockState s = stairAt(world, x + dir.offsetX(), y + dir.offsetY(), z + dir.offsetZ());
        return s == null
                || s.get(Properties.FACING) != state.get(Properties.FACING)
                || s.get(Properties.HALF) != state.get(Properties.HALF);
    }

    private static BlockState stairAt(Dimension world, int x, int y, int z) {
        BlockState s = Blocks.getState(world.getBlock(x, y, z));
        return s.getValues().containsKey(Properties.STAIR_SHAPE) ? s : null;
    }
}
