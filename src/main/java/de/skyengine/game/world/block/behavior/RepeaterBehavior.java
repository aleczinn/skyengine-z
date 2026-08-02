package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;

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
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING, Direction.fromYaw(ctx.playerYaw()))
                .with(Properties.DELAY, 1)
                .with(Properties.POWERED, false);
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        int delay = state.get(Properties.DELAY) % 4 + 1;
        world.setBlock(x, y, z, state.with(Properties.DELAY, delay).getId(), false);
        return true;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (hasInput(world, x, y, z, state) != state.get(Properties.POWERED)
                && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, state.get(Properties.DELAY) * 2);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
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
            if (!input) world.scheduleTick(x, y, z, state.get(Properties.DELAY) * 2);
        }
    }

    private static void switchOutput(World world, int x, int y, int z, BlockState state, boolean powered) {
        world.setBlock(x, y, z, state.with(Properties.POWERED, powered).getId(), true);
        /* Starkes Ziel vor dem Ausgang: dessen Nachbarn erfahren die Flanke nur so. */
        Direction out = state.get(Properties.FACING);
        int tx = x + out.offsetX(), tz = z + out.offsetZ();
        if (Blocks.getState(world.getBlock(tx, y, tz)).isOpaqueCube()) {
            world.updateNeighbors(tx, y, tz);
        }
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
}
