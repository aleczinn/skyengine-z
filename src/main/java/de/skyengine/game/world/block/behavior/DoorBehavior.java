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

    /** Tür passt nur, wenn über dem Zielfeld noch Platz für den oberen Teil ist. */
    @Override
    public boolean canPlace(PlacementContext ctx, BlockState state) {
        return ctx.world().getBlock(ctx.x(), ctx.y() + 1, ctx.z()) == Blocks.AIR;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        /* Tür schließt an der dem Spieler zugewandten (vorderen) Kante an -> Blickrichtung invertiert. */
        Direction facing = Direction.fromYaw(ctx.playerYaw()).opposite();
        DoorHinge hinge = hinge(ctx, facing);

        /* Nur den unteren State berechnen - der obere Teil kommt in onPlaced (nach der Validierung),
           damit bei abgelehnter Platzierung kein schwebender Oberteil zurückbleibt. */
        return state.with(Properties.FACING, facing)
                .with(Properties.HALF, BlockHalf.BOTTOM)
                .with(Properties.OPEN, false)
                .with(Properties.HINGE, hinge);
    }

    /** Setzt den oberen Türteil, nachdem der untere validiert platziert wurde. */
    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        if (world.getBlock(x, y + 1, z) == Blocks.AIR) {
            world.setBlock(x, y + 1, z, state.with(Properties.HALF, BlockHalf.TOP).getId(), false);
        }
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

    /**
     * Anschlag aus der Klickposition: projiziert den Trefferpunkt auf die „Rechts"-Achse
     * (Spielersicht auf die Türvorderseite). Klick auf die rechte Hälfte -> Hinge RIGHT,
     * sonst LEFT. So bestimmt der Spieler beim Platzieren die Öffnungsseite.
     */
    private static DoorHinge hinge(PlacementContext ctx, Direction facing) {
        Direction right = facing.rotateYCCW();
        double proj = (ctx.hitX() - 0.5) * right.offsetX() + (ctx.hitZ() - 0.5) * right.offsetZ();
        return proj > 0 ? DoorHinge.RIGHT : DoorHinge.LEFT;
    }
}
