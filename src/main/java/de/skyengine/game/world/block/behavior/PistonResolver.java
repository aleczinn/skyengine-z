package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.PistonReaction;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Löst auf, was ein Kolben-Schub bewegt: heute ein Linien-Scan ab der Kopf-Zelle, die API
 * liefert aber bewusst eine Positions-MENGE in Schreib-Reihenfolge — die spätere
 * Slime-Verkettung erweitert nur diese Klasse.
 *
 * <p><b>Vorab-Totalvalidierung:</b> jede Quelle, jedes Ziel und jede Destroy-Zelle wird über
 * {@code World.isPositionEditable} geprüft, BEVOR irgendetwas geschrieben wird — ein halb
 * fehlgeschlagener setBlock-Lauf an der Ladefront hinterließe sonst Block-Duplikate.
 */
public final class PistonResolver {

    /** MC-Schublimit. */
    public static final int MAX_PUSH = 12;

    /** Kein Block zu ziehen (resolvePull). */
    public static final long NO_PULL = Long.MIN_VALUE;

    /**
     * @param moves    Quellzellen der geschobenen Blöcke in Schreib-Reihenfolge (fern → nah);
     *                 das Ziel jeder Quelle ist die Nachbarzelle in Schub-Richtung
     * @param destroys zu zerbrechende Zellen (Drop + Überschreiben), heute höchstens eine
     * @param blockedByMoving Blocker war ein bewegter Block — der Aufrufer pollt dann
     */
    public record Result(long[] moves, long[] destroys, boolean blocked, boolean blockedByMoving) {

        static Result blocked(boolean byMoving) {
            return new Result(null, null, true, byMoving);
        }
    }

    /** Auflösung eines Extend ab der Basis (bx,by,bz) in Richtung {@code facing}. */
    public static Result resolveExtend(World world, int bx, int by, int bz, Direction facing) {
        List<Long> moves = new ArrayList<>();
        long destroy = NO_PULL;
        int x = bx, y = by, z = bz;
        while (true) {
            x += facing.offsetX();
            y += facing.offsetY();
            z += facing.offsetZ();
            if (!world.isPositionEditable(x, y, z)) return Result.blocked(false);

            BlockState state = Blocks.getState(world.getBlock(x, y, z));
            /* Luft/Fluid beendet die Kette: die Zelle wird vom Ziel-Write des letzten
               Kettenglieds still überschrieben (Fluid ohne Drop; Nachbarwasser fließt
               über die regulären Fluid-Ticks zurück). */
            if (state.isAir() || state.isFluid()) break;

            if (isBusyMoving(state)) return Result.blocked(true);
            PistonReaction reaction = state.getBlock().getPistonReaction();
            if (reaction == PistonReaction.BLOCK) return Result.blocked(false);
            /* Eine AUSGEFAHRENE Basis ist unverschiebbar (ihr Kopf bliebe zurück) —
               State-abhängiger Sonderfall, den das block-globale piston_reaction nicht kennt. */
            if (isExtendedPistonBase(state)) return Result.blocked(false);
            if (reaction == PistonReaction.DESTROY) {
                destroy = BlockPos.asLong(x, y, z);
                break;
            }

            moves.add(BlockPos.asLong(x, y, z));
            if (moves.size() > MAX_PUSH) return Result.blocked(false);
        }

        /* Schreib-Reihenfolge fern -> nah. */
        long[] moveArr = new long[moves.size()];
        for (int i = 0; i < moveArr.length; i++) moveArr[i] = moves.get(moves.size() - 1 - i);
        long[] destroyArr = destroy == NO_PULL ? new long[0] : new long[]{destroy};
        return new Result(moveArr, destroyArr, false, false);
    }

    /**
     * Sticky-Einzug: darf die Zelle (px,py,pz) vor dem Kopf mitgezogen werden?
     * {@code NO_PULL}, wenn nicht (Luft, Fluid, destroy-Blöcke bleiben stehen, block,
     * ausgefahrene Basen, bewegte Blöcke, Ladefront).
     */
    public static long resolvePull(World world, int px, int py, int pz) {
        if (!world.isPositionEditable(px, py, pz)) return NO_PULL;
        BlockState state = Blocks.getState(world.getBlock(px, py, pz));
        if (state.isAir() || state.isFluid()) return NO_PULL;
        if (isBusyMoving(state)) return NO_PULL;
        if (state.getBlock().getPistonReaction() != PistonReaction.NORMAL) return NO_PULL;
        if (isExtendedPistonBase(state)) return NO_PULL;
        return BlockPos.asLong(px, py, pz);
    }

    private static boolean isBusyMoving(BlockState state) {
        return state.getBlock().getBlockEntityType() == BlockEntities.PISTON_MOVING;
    }

    private static boolean isExtendedPistonBase(BlockState state) {
        return state.getValues().containsKey(Properties.EXTENDED)
                && state.get(Properties.EXTENDED);
    }

    private PistonResolver() {}
}
