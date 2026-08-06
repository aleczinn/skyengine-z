package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;
import de.skyengine.game.world.tick.TickPriority;

/**
 * Verstärker (Repeater): richtungsgebundene Diode mit einstellbarer Verzögerung.
 * Konvention hier: <b>FACING = Ausgangsrichtung</b> (die Blickrichtung des Spielers beim
 * Platzieren), Eingang ist die Gegenseite — Vanillas facing zeigt genau andersherum, die
 * Blockstate-Rotationen in der JSON sind entsprechend gemappt.
 *
 * <p>Timing über den vorhandenen Scheduler, jede Flanke plant ihren EIGENEN Tick nach
 * {@code delay × 2} Game-Ticks (Vanilla-Logik): die Ein-Flanke schaltet verzögert ein —
 * ist der Puls beim Feuern schon wieder weg, wird das Aus sofort nachgeplant (kurze Pulse
 * werden so auf die Verzögerungslänge gestreckt); sonst plant die Aus-Flanke selbst über
 * {@code onNeighborUpdate}. Kein Locking (kommt mit der Komparator-Etappe, die die
 * Seiteneingang-Unterscheidung ohnehin braucht — ein späteres locked-Property ist dank
 * Codec-Toleranz save-kompatibel additiv).
 */
public final class RepeaterBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        BlockState placed = state.with(Properties.FACING, Direction.fromYaw(ctx.playerYaw()))
                .with(Properties.DELAY, 1)
                .with(Properties.POWERED, false);
        /* RepeaterBlock#getStateForPlacement schreibt LOCKED bereits in den Placement-State.
           Ein nachtraeglicher Korrektur-State waere fuer Observer und Update-Reihenfolge sichtbar. */
        return placed.with(Properties.LOCKED,
                isLocked(ctx.world(), ctx.x(), ctx.y(), ctx.z(), placed));
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        notifyStrongTarget(world, x, y, z, state);
    }

    @Override
    public void onRemoved(World world, int x, int y, int z,
                          BlockState oldState, BlockState newState) {
        notifyStrongTarget(world, x, y, z, oldState);
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        int delay = state.get(Properties.DELAY) % 4 + 1;
        /* MIT Nachbar-Update (MCs Flag 3): die Verzoegerung steckt im State, ein danebenstehender
           Beobachter erkennt die Aenderung nur, wenn er geweckt wird. Nebenbei bewertet der
           Verstaerker sich dabei selbst neu (Locking + Tick mit der neuen Verzoegerung). */
        world.setBlock(x, y, z, state.with(Properties.DELAY, delay).getId(), true);
        return true;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        /* Locking (MC): eine seitliche Diode (Verstärker/Komparator), die auf uns zeigt und
           Signal führt, friert den Ausgang ein — solange gesperrt, werden Eingangs-Flanken
           ignoriert. Beim Entsperren wird der Zustand frisch bewertet. */
        boolean locked = isLocked(world, x, y, z, state);
        if (locked != state.get(Properties.LOCKED)) {
            state = state.with(Properties.LOCKED, locked);
        }
        if (!locked && hasInput(world, x, y, z, state) != state.get(Properties.POWERED)
                && !world.willTickThisTick(x, y, z)) {
            TickPriority priority;
            if (shouldPrioritize(world, x, y, z, state)) {
                priority = TickPriority.EXTREMELY_HIGH;
            } else if (state.get(Properties.POWERED)) {
                priority = TickPriority.VERY_HIGH;
            } else {
                priority = TickPriority.HIGH;
            }
            world.scheduleTick(x, y, z, state.get(Properties.DELAY) * 2, priority);
        }
        return state;
    }

    /** Vanilla DiodeBlock#shouldPrioritize, uebersetzt auf FACING=Ausgang. */
    private static boolean shouldPrioritize(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int nx = x + out.offsetX(), nz = z + out.offsetZ();
        BlockState neighbor = Blocks.getState(world.getBlock(nx, y, nz));
        boolean diode = neighbor.getValues().containsKey(Properties.DELAY)
                || neighbor.getValues().containsKey(Properties.MODE);
        return diode && neighbor.get(Properties.FACING) != out.opposite();
    }

    /** Gesperrt, wenn links oder rechts eine Diode mit Signal auf diesen Verstärker zeigt. */
    private static boolean isLocked(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        return isLockingSide(world, x, y, z, out.rotateYCW())
                || isLockingSide(world, x, y, z, out.rotateYCCW());
    }

    private static boolean isLockingSide(World world, int x, int y, int z, Direction side) {
        int sx = x + side.offsetX(), sz = z + side.offsetZ();
        BlockState neighbor = Blocks.getState(world.getBlock(sx, y, sz));
        boolean diode = neighbor.getValues().containsKey(Properties.DELAY)
                || neighbor.getValues().containsKey(Properties.MODE);
        if (!diode) return false;
        /* Die Diode muss mit ihrem AUSGANG auf uns zeigen und Signal führen. */
        if (neighbor.get(Properties.FACING) != side.opposite()) return false;
        return neighbor.getBlock().getWeakPower(world, sx, y, sz, neighbor, side.opposite()) > 0;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        if (state.get(Properties.LOCKED)) return;   // gesperrt: Ausgang eingefroren
        boolean powered = state.get(Properties.POWERED);
        boolean input = hasInput(world, x, y, z, state);
        if (powered && !input) {
            switchOutput(world, x, y, z, state, false);
        } else if (!powered) {
            switchOutput(world, x, y, z, state, true);
            /* NUR bei schon wieder totem Eingang (kurzer Puls) das Aus sofort nachplanen —
               Vanillas Puls-Streckung. Bei stehendem Eingang plant die AUS-Flanke selbst
               (onNeighborUpdate). Ein unbedingter Kontroll-Tick hier wäre falsch: er stünde
               beim Abfallen des Eingangs noch aus, blockierte per Dedup den echten Aus-Tick
               und schaltete beim Feuern sofort — die Verzögerung hinge dann an der EIN-
               statt an der AUS-Flanke (gemessen: Staub vor und hinter dem Verstärker
               gingen gleichzeitig aus). */
            if (!input) world.scheduleTick(x, y, z, state.get(Properties.DELAY) * 2,
                    TickPriority.VERY_HIGH);
        }
    }

    private static void switchOutput(World world, int x, int y, int z, BlockState state, boolean powered) {
        world.setBlock(x, y, z, state.with(Properties.POWERED, powered).getId(), false);
        /* Exakter DiodeBlock.updateNeighborsInFront-Pfad: Ausgangszelle plus ihre fünf vom
           Repeater wegführenden Nachbarn, ausschließlich als allgemeine Updates. */
        notifyStrongTarget(world, x, y, z, state);
    }

    /** Vanillas DiodeBlock#updateNeighborsInFront fuer Platzierung, Flanke und Entfernung. */
    private static void notifyStrongTarget(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        world.updateDirectionalOutputNeighbors(x, y, z, out);
    }

    /** Signal am Eingang (Gegenseite von FACING)? */
    private static boolean hasInput(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int ix = x - out.offsetX(), iz = z - out.offsetZ();
        return RedstonePower.emittedSignal(world, ix, y, iz, out, false) > 0;
    }

    /* Ausgang: volle 15, schwach UND stark, nur in FACING-Richtung. */

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED) && side == state.get(Properties.FACING) ? 15 : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return weakPower(world, x, y, z, state, side);
    }

    /** Staub verbindet sich nur mit Ein- und Ausgang, nicht mit den Flanken. */
    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        Direction facing = state.get(Properties.FACING);
        return side == facing || side == facing.opposite();
    }

    @Override
    public boolean isRedstoneSignalSource() {
        return true;
    }
}
