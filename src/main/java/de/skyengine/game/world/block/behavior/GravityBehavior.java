package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Schwerkraft (Sand, Kies): der Block fällt, solange darunter Luft oder ein Fluid ist.
 *
 * <p>Der geplante Tick entfernt den Block und spawnt eine {@link
 * de.skyengine.game.entity.FallingBlockEntity}, die flüssig fällt und beim Aufprall
 * wieder zu einem Block wird (ggf. unter Verdrängung eines Fluids, wie in MC).
 *
 * <p>Ausgelöst beim Platzieren und bei Nachbar-Updates (z.B. wenn der Block darunter
 * abgebaut wird oder Wasser darunter fließt).
 */
public final class GravityBehavior implements BlockBehavior {

    /** Ticks zwischen zwei Fall-Schritten (20 TPS). */
    private static final int FALL_DELAY = 2;

    @Override
    public void onPlaced(Dimension world, int x, int y, int z, BlockState state) {
        scheduleIfUnsupported(world, x, y, z);
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        scheduleIfUnsupported(world, x, y, z);
        return state;
    }

    @Override
    public void scheduledTick(Dimension world, int x, int y, int z, BlockState state) {
        if (!Blocks.canFallInto(world.getBlock(x, y - 1, z))) return; // Boden erreicht -> liegen bleiben
        world.particles().fallingDust(x + 0.5, y, z + 0.5, state);
        world.setBlock(x, y, z, Blocks.AIR);                          // Block entfernen ...
        world.spawnFallingBlock(x, y, z, state.getId());              // ... und als Entity flüssig fallen lassen
    }

    /** Plant einen Fall-Tick nur, wenn unter dem Block Luft/Fluid ist (kein Tick für ruhende Blöcke). */
    private static void scheduleIfUnsupported(Dimension world, int x, int y, int z) {
        if (Blocks.canFallInto(world.getBlock(x, y - 1, z))) world.scheduleTick(x, y, z, FALL_DELAY);
    }
}
