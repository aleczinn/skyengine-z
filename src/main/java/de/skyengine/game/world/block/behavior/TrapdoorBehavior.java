package de.skyengine.game.world.block.behavior;

import de.skyengine.audio.BlockOpenSound;
import de.skyengine.audio.SoundManager;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;

/**
 * Das Falltürspezifische: Ausrichtung beim Platzieren und der Rechtsklick, der sie auf- und
 * zuklappt. Anders als die Tür ist sie EINTEILIG — kein {@code parts}, kein HINGE.
 *
 * <p>Platzierungsregel verbatim aus Vanillas {@code TrapDoorBlock.getStateForPlacement}: bei
 * einem Klick auf eine SEITENfläche hängt sie an dieser Seite (FACING = Normale) und die Höhe des
 * Trefferpunkts entscheidet oben/unten; bei einem Klick auf Ober- oder Unterseite zeigt sie zum
 * Spieler und sitzt am Boden bzw. an der Decke.
 */
public final class TrapdoorBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    /** Wie bei der Tür: die Eisen-Falltür lässt sich nicht von Hand öffnen. */
    private final boolean handOpenable;

    public TrapdoorBehavior(boolean handOpenable) {
        this.handOpenable = handOpenable;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        BlockState placed = state.with(Properties.OPEN, false);
        if (ctx.faceY() != 0) {
            /* Ober-/Unterseite geklickt: Blickrichtung invertiert, Hälfte aus der Klickfläche. */
            return placed.with(Properties.FACING, Direction.fromYaw(ctx.playerYaw()).opposite())
                    .with(Properties.HALF, ctx.faceY() > 0 ? BlockHalf.BOTTOM : BlockHalf.TOP);
        }
        /* Seitenfläche: die Normale zeigt vom Träger weg — daran hängt die Falltür. */
        return placed.with(Properties.FACING, horizontalOf(ctx.faceX(), ctx.faceZ()))
                .with(Properties.HALF, ctx.hitY() > 0.5 ? BlockHalf.TOP : BlockHalf.BOTTOM);
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        /* false = Klick NICHT verbraucht, der Aufrufer platziert dann normal weiter. */
        if (!this.handOpenable) return false;

        boolean open = !state.get(Properties.OPEN);
        world.setBlock(x, y, z, state.with(Properties.OPEN, open).getId(), false);
        playOpenSound(world, x, y, z, state, open);
        return true;
    }

    /**
     * Redstone-Empfänger wie die Tür: OPEN folgt der Signal-Flanke (POWERED = Speicher),
     * Handbedienung bleibt dazwischen unberührt. Einteilig, daher einfacher.
     */
    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        boolean powered = RedstonePower.isReceiving(world, x, y, z);
        if (powered == state.get(Properties.POWERED)) return state;

        if (powered != state.get(Properties.OPEN)) playOpenSound(world, x, y, z, state, powered);
        return state.with(Properties.OPEN, powered).with(Properties.POWERED, powered);
    }

    /** Nullbar wie bei der Tür: ohne SoundManager (Weltgen-Tests) bleibt es still. */
    private static void playOpenSound(World world, int x, int y, int z, BlockState state, boolean open) {
        SoundManager sound = world.getSoundManager();
        BlockOpenSound set = state.getBlock().getOpenSound();
        if (sound != null && set != null) {
            if (open) sound.playBlockOpen(set, x + 0.5, y + 0.5, z + 0.5);
            else sound.playBlockClose(set, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    /** Getroffene Seiten-Normale -> horizontale Richtung (Fallback NORTH), wie in AttachBehavior. */
    private static Direction horizontalOf(int faceX, int faceZ) {
        if (faceX > 0) return Direction.EAST;
        if (faceX < 0) return Direction.WEST;
        if (faceZ > 0) return Direction.SOUTH;
        if (faceZ < 0) return Direction.NORTH;
        return Direction.NORTH;
    }
}
