package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.HashMap;
import java.util.Map;

/**
 * Beobachter: feuert einen 2-Tick-Puls (stark, aus der Rückseite), wenn sich der
 * Block-STATE der Zelle vor seinem Gesicht ändert. FACING zeigt zum beobachteten Block
 * (beim Platzieren in Blickrichtung — das Gesicht zeigt vom Spieler weg, wie MC).
 *
 * <p><b>Änderungs-Erkennung ohne gerichteten Hook:</b> {@code onNeighborUpdate} kennt den
 * Auslöser nicht — der Beobachter vergleicht deshalb die beobachtete Zelle mit dem zuletzt
 * gesehenen State (transiente Map, Muster PressurePlate). Erstkontakt (Platzieren,
 * Chunk-Load) speichert nur, OHNE Puls — bewusste Abweichung von MCs
 * Chunk-Load-Feuern.
 */
public final class ObserverBehavior implements BlockBehavior {

    /** Position -> zuletzt beobachtete State-ID. Nur Tick-Thread, deshalb ungesichert. */
    private final Map<Long, Integer> observed = new HashMap<>();

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING_ALL, lookDirection(ctx))
                .with(Properties.POWERED, false);
    }

    /** Blickrichtung des Spielers (Gesicht zeigt vom Spieler WEG — invers zum Kolben). */
    private static Direction lookDirection(PlacementContext ctx) {
        if (ctx.playerPitch() > 45) return Direction.DOWN;
        if (ctx.playerPitch() < -45) return Direction.UP;
        return Direction.fromYaw(ctx.playerYaw());
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        /* Initialen Zustand merken — die erste echte Änderung soll pulsen, nicht das Platzieren. */
        this.observed.put(key(x, y, z), watchedState(world, x, y, z, state));
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        long key = key(x, y, z);
        int watched = watchedState(world, x, y, z, state);
        Integer last = this.observed.put(key, watched);
        if (last != null && last != watched && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, 2);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        boolean powered = state.get(Properties.POWERED);
        world.setBlock(x, y, z, state.with(Properties.POWERED, !powered).getId(), true);
        this.notifyStrongTarget(world, x, y, z, state);
        if (!powered) world.scheduleTick(x, y, z, 2);   // Puls-Ende nach 2 Ticks
    }

    /** Zweiter Ring um das stark gepowerte Ziel hinter dem Ausgang (Leitung durch den Block). */
    private void notifyStrongTarget(World world, int x, int y, int z, BlockState state) {
        Direction back = state.get(Properties.FACING_ALL).opposite();
        world.updateNeighbors(x + back.offsetX(), y + back.offsetY(), z + back.offsetZ());
    }

    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        this.observed.remove(key(x, y, z));
    }

    /* --- Ausgang: 15 stark UND schwach, nur aus der Rückseite --- */

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED)
                && side == state.get(Properties.FACING_ALL).opposite() ? 15 : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return weakPower(world, x, y, z, state, side);
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return side == state.get(Properties.FACING_ALL).opposite();
    }

    private static int watchedState(World world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        return world.getBlock(x + f.offsetX(), y + f.offsetY(), z + f.offsetZ());
    }

    private static long key(int x, int y, int z) {
        return de.skyengine.game.world.block.BlockPos.asLong(x, y, z);
    }
}
