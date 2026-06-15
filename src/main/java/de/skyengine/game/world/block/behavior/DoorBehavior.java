package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.DoorHinge;
import de.skyengine.game.world.block.state.Properties;

/**
 * Zwei-Block-Tür rein über BlockStates: HALF (bottom/top) + FACING + OPEN + HINGE.
 *
 * <ul>
 *   <li>Platzieren setzt den unteren Teil und automatisch den oberen darüber.</li>
 *   <li>Fehlt die jeweils andere Hälfte (z.B. nach Abbau), entfernt sich der Rest selbst
 *       (über das vertikale Nachbar-Update).</li>
 *   <li>Rechtsklick toggelt OPEN für beide Hälften.</li>
 * </ul>
 *
 * „Ist das eine Tür?" wird über das Vorhandensein der HINGE-Property erkannt — kein instanceof,
 * kein Door-spezifischer Code im Engine-Core.
 */
public final class DoorBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        /* Tür schließt an der dem Spieler zugewandten (vorderen) Kante an -> Blickrichtung invertiert. */
        Direction facing = Direction.fromYaw(ctx.playerYaw()).opposite();
        DoorHinge hinge = hinge(ctx.world(), ctx.x(), ctx.y(), ctx.z(), facing);

        BlockState bottom = state.with(Properties.FACING, facing)
                .with(Properties.HALF, BlockHalf.BOTTOM)
                .with(Properties.OPEN, false)
                .with(Properties.HINGE, hinge);

        /* Oberen Teil mitsetzen (ohne Nachbar-Kaskade, da der untere noch nicht steht). */
        if (ctx.world().getBlock(ctx.x(), ctx.y() + 1, ctx.z()) == Blocks.AIR) {
            ctx.world().setBlock(ctx.x(), ctx.y() + 1, ctx.z(),
                    bottom.with(Properties.HALF, BlockHalf.TOP).getId(), false);
        }
        return bottom;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        BlockHalf half = state.get(Properties.HALF);
        int otherY = half == BlockHalf.BOTTOM ? y + 1 : y - 1;
        BlockHalf needed = half == BlockHalf.BOTTOM ? BlockHalf.TOP : BlockHalf.BOTTOM;

        BlockState other = Blocks.getState(world.getBlock(x, otherY, z));
        if (!isDoor(other) || other.get(Properties.HALF) != needed) {
            return Blocks.getState(Blocks.AIR);   // andere Hälfte weg -> selbst entfernen
        }
        return state;
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        boolean open = !state.get(Properties.OPEN);
        BlockHalf half = state.get(Properties.HALF);
        int otherY = half == BlockHalf.BOTTOM ? y + 1 : y - 1;

        world.setBlock(x, y, z, state.with(Properties.OPEN, open).getId(), false);
        BlockState other = Blocks.getState(world.getBlock(x, otherY, z));
        if (isDoor(other)) {
            world.setBlock(x, otherY, z, other.with(Properties.OPEN, open).getId(), false);
        }
        return true;
    }

    /** Türerkennung ohne instanceof: ein Block ist eine Tür, wenn sein State HINGE trägt. */
    private static boolean isDoor(BlockState state) {
        return state.getValues().containsKey(Properties.HINGE);
    }

    private static DoorHinge hinge(World world, int x, int y, int z, Direction facing) {
        Direction left = facing.rotateYCCW();
        BlockState neighbor = Blocks.getState(world.getBlock(x + left.offsetX(), y, z + left.offsetZ()));
        return (isDoor(neighbor) && neighbor.get(Properties.HINGE) == DoorHinge.LEFT)
                ? DoorHinge.RIGHT : DoorHinge.LEFT;
    }
}
