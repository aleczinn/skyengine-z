package de.skyengine.game.world.block.behavior;

import de.skyengine.audio.BlockOpenSound;
import de.skyengine.audio.SoundManager;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.DoorHinge;
import de.skyengine.game.world.block.state.Properties;

/**
 * Das Türspezifische an einer Tür: FACING + OPEN + HINGE beim Platzieren und der Rechtsklick,
 * der beide Hälften gemeinsam öffnet.
 *
 * <p>Das Zweiteilige (untere/obere Hälfte setzen, Platzprüfung, Selbstentfernen bei fehlender
 * Gegenhälfte) steckt NICHT mehr hier, sondern deklarativ in der {@code parts}-Sektion der
 * Block-JSON → {@link PartsBehavior}.
 *
 * <p>„Ist das eine Tür?" wird über das Vorhandensein der HINGE-Property erkannt — kein instanceof,
 * kein Door-spezifischer Code im Engine-Core.
 */
public final class DoorBehavior implements BlockBehavior {

    /**
     * Ob ein Rechtsklick die Tür öffnet. Die Eisentür kann das in Minecraft NICHT — sie braucht
     * ein Signal. Kommt aus dem Block-JSON-Feld {@code hand_openable}.
     */
    private final boolean handOpenable;

    public DoorBehavior(boolean handOpenable) {
        this.handOpenable = handOpenable;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        /* Tür schließt an der dem Spieler zugewandten (vorderen) Kante an -> Blickrichtung invertiert. */
        Direction facing = Direction.fromYaw(ctx.playerYaw()).opposite();

        /* HALF setzt das PartsBehavior (parts-Sektion der Block-JSON); hier nur das Türspezifische. */
        return state.with(Properties.FACING, facing)
                .with(Properties.OPEN, false)
                .with(Properties.HINGE, hinge(ctx, facing));
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        /* false = Klick NICHT verbraucht, der Aufrufer platziert dann normal weiter. */
        if (!this.handOpenable) return false;

        boolean open = !state.get(Properties.OPEN);
        BlockHalf half = state.get(Properties.HALF);
        int otherY = half == BlockHalf.BOTTOM ? y + 1 : y - 1;

        world.setBlock(x, y, z, state.with(Properties.OPEN, open).getId(), false);
        BlockState other = Blocks.getState(world.getBlock(x, otherY, z));
        if (isDoor(other)) {
            world.setBlock(x, otherY, z, other.with(Properties.OPEN, open).getId(), false);
        }

        /* Ein Sound für beide Hälften, an der angeklickten. Nullbar wie beim TNT-Fuse: ohne
           SoundManager (Weltgen-Tests) oder ohne Sound-Satz bleibt es einfach still. */
        SoundManager sound = world.getSoundManager();
        BlockOpenSound set = state.getBlock().getOpenSound();
        if (sound != null && set != null) {
            if (open) sound.playBlockOpen(set, x + 0.5, y + 0.5, z + 0.5);
            else sound.playBlockClose(set, x + 0.5, y + 0.5, z + 0.5);
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
