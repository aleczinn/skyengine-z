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
 * <p>Timing als <b>Puls-Latch</b> über den vorhandenen Scheduler: eine Eingangsflanke plant
 * einen Tick nach {@code delay × 2} Game-Ticks. Beim Einschalten wird die Flanke gelatcht
 * (unabhängig vom aktuellen Eingang — kurze Pulse werden wie in Vanilla auf die
 * Verzögerungslänge gestreckt) und sofort der Aus-Check nachgeplant; ausgeschaltet wird
 * nur, wenn der Eingang wirklich weg ist. Kein Locking (kommt mit der Komparator-Etappe,
 * die die Seiteneingang-Unterscheidung ohnehin braucht — ein späteres locked-Property ist
 * dank Codec-Toleranz save-kompatibel additiv).
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
        if (!powered) {
            /* Flanke latchen: einschalten auch, wenn der Puls schon wieder weg ist —
               Vanilla streckt kurze Pulse auf die Verzögerungslänge. Der Aus-Check
               folgt sofort im eigenen Takt. */
            switchOutput(world, x, y, z, state, true);
            world.scheduleTick(x, y, z, state.get(Properties.DELAY) * 2);
        } else if (!hasInput(world, x, y, z, state)) {
            switchOutput(world, x, y, z, state, false);
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
