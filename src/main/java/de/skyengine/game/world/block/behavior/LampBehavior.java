package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;

/**
 * Redstone-Lampe: LIT folgt dem empfangenen Signal — an sofort (reine State-Rückgabe im
 * Nachbar-Update), aus mit 4 Ticks Verzögerung über einen geplanten Tick (MC-Verhalten;
 * dadurch flackert sie an einer schnellen Clock nicht). Das Leuchten selbst kommt aus
 * {@code light_level} + LIT-Konvention in {@code Block.getLuminance}.
 */
public final class LampBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    /** Vanilla RedstoneLampBlock#getStateForPlacement: kein beobachtbarer dunkler Zwischenstate. */
    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.LIT,
                RedstonePower.isReceiving(ctx.world(), ctx.x(), ctx.y(), ctx.z()));
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        boolean powered = RedstonePower.isReceiving(world, x, y, z);
        boolean lit = state.get(Properties.LIT);
        if (powered && !lit) return state.with(Properties.LIT, true);
        if (!powered && lit && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, 4);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        /* Tolerantes Feuern: erneut pruefen — kam das Signal zurueck, bleibt sie an. */
        if (!state.get(Properties.LIT) || RedstonePower.isReceiving(world, x, y, z)) return;
        /* Niemand liest den Lampen-State — kein Nachbar-Ring noetig, Licht/Mesh macht setBlock. */
        world.setBlockWithShapeUpdates(x, y, z, state.with(Properties.LIT, false).getId());
    }
}
