package de.skyengine.game.world.block.behavior;

import de.skyengine.audio.BlockOpenSound;
import de.skyengine.audio.SoundManager;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;

/** Vanilla-Zustandsübergänge eines hölzernen Zauntors. */
public final class FenceGateBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        Direction facing = Direction.fromYaw(ctx.playerYaw());
        boolean powered = RedstonePower.isReceiving(ctx.world(), ctx.x(), ctx.y(), ctx.z());
        return state.with(Properties.FACING, facing)
                .with(Properties.OPEN, powered)
                .with(Properties.POWERED, powered)
                .with(Properties.IN_WALL, this.isInWall(ctx.world(), ctx.x(), ctx.y(), ctx.z(), facing));
    }

    @Override
    public boolean onUse(Dimension world, int x, int y, int z, BlockState state, float playerYaw) {
        boolean open = state.get(Properties.OPEN);
        BlockState changed = state;
        if (open) {
            changed = changed.with(Properties.OPEN, false);
        } else {
            Direction playerFacing = Direction.fromYaw(playerYaw);
            if (state.get(Properties.FACING) == playerFacing.opposite()) {
                changed = changed.with(Properties.FACING, playerFacing);
            }
            changed = changed.with(Properties.OPEN, true);
        }
        world.setBlockWithShapeUpdates(x, y, z, changed.getId());
        playSound(world, x, y, z, state, !open);
        return true;
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        Direction facing = state.get(Properties.FACING);
        boolean inWall = this.isInWall(world, x, y, z, facing);
        boolean powered = RedstonePower.isReceiving(world, x, y, z);
        BlockState changed = state;
        if (inWall != state.get(Properties.IN_WALL)) changed = changed.with(Properties.IN_WALL, inWall);
        if (powered != state.get(Properties.POWERED)) {
            if (powered != state.get(Properties.OPEN)) playSound(world, x, y, z, state, powered);
            changed = changed.with(Properties.POWERED, powered).with(Properties.OPEN, powered);
        }
        return changed;
    }

    private boolean isInWall(Dimension world, int x, int y, int z, Direction facing) {
        if (facing.axis() == Direction.Axis.Z) {
            return isWall(world, x - 1, y, z) || isWall(world, x + 1, y, z);
        }
        return isWall(world, x, y, z - 1) || isWall(world, x, y, z + 1);
    }

    private static boolean isWall(Dimension world, int x, int y, int z) {
        return "wall".equals(Blocks.getState(world.getBlock(x, y, z)).getBlock().getConnectionGroup());
    }

    private static void playSound(Dimension world, int x, int y, int z, BlockState state, boolean open) {
        SoundManager sounds = world.getSoundManager();
        BlockOpenSound set = state.getBlock().getOpenSound();
        if (sounds == null || set == null) return;
        if (open) sounds.playBlockOpen(set, x + 0.5, y + 0.5, z + 0.5);
        else sounds.playBlockClose(set, x + 0.5, y + 0.5, z + 0.5);
    }
}
