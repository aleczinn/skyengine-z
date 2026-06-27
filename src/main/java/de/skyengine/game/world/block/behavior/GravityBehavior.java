package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Schwerkraft (Sand, Kies): der Block fällt nach unten, solange darunter Luft ist.
 *
 * <p>Block-basiert über den Tick-Scheduler (Phase 1.1) - der Block „springt" pro
 * {@link #FALL_DELAY} Ticks eine Position tiefer. Erster Konsument des Schedulers.
 * Eine flüssig fallende Entity (smooth) ist ein späteres Upgrade (Phase 1.4).
 *
 * <p>Ausgelöst beim Platzieren und bei Nachbar-Updates (z.B. wenn der Block darunter
 * abgebaut wird); der geplante Tick prüft erneut und fällt ggf. eine Position tiefer.
 */
public final class GravityBehavior implements BlockBehavior {

    /** Ticks zwischen zwei Fall-Schritten (20 TPS). */
    private static final int FALL_DELAY = 2;

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        scheduleIfUnsupported(world, x, y, z);
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        scheduleIfUnsupported(world, x, y, z);
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        if (world.getBlock(x, y - 1, z) != Blocks.AIR) return;   // Boden erreicht -> liegen bleiben
        world.setBlock(x, y, z, Blocks.AIR);                     // Block entfernen ...
        world.spawnFallingBlock(x, y, z, state.getId());         // ... und als Entity flüssig fallen lassen
    }

    /** Plant einen Fall-Tick nur, wenn unter dem Block Luft ist (kein Tick für ruhende Blöcke). */
    private static void scheduleIfUnsupported(World world, int x, int y, int z) {
        if (world.getBlock(x, y - 1, z) == Blocks.AIR) world.scheduleTick(x, y, z, FALL_DELAY);
    }
}
