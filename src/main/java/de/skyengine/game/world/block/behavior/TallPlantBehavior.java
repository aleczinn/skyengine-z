package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Zwei Blöcke hohe Pflanze (tall_grass): HALF (bottom/top) nach dem Tür-Muster.
 *
 * <ul>
 *   <li>Platzieren setzt die untere Hälfte und automatisch die obere darüber.</li>
 *   <li>Fehlt die jeweils andere Hälfte (z.B. nach Abbau), entfernt sich der Rest selbst
 *       über das vertikale Nachbar-Update — eine Hälfte abbauen entfernt also beide.</li>
 *   <li>Die untere Hälfte braucht zusätzlich eine feste Stütze darunter (wie PlantBehavior);
 *       PlantBehavior selbst ist hier NICHT nutzbar, weil die obere Hälfte auf der
 *       nicht-soliden unteren steht und sich sonst sofort selbst entfernen würde.</li>
 * </ul>
 */
public final class TallPlantBehavior implements BlockBehavior {

    /** Passt nur, wenn über dem Zielfeld Platz ist und darunter ein fester Block liegt. */
    @Override
    public boolean canPlace(PlacementContext ctx, BlockState state) {
        return ctx.world().getBlock(ctx.x(), ctx.y() + 1, ctx.z()) == Blocks.AIR
                && Blocks.getState(ctx.world().getBlock(ctx.x(), ctx.y() - 1, ctx.z())).isSolid();
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        /* Nur die untere Hälfte berechnen - die obere kommt in onPlaced (nach der Validierung). */
        return state.with(Properties.HALF, BlockHalf.BOTTOM);
    }

    /** Setzt die obere Hälfte, nachdem die untere validiert platziert wurde. */
    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        if (world.getBlock(x, y + 1, z) == Blocks.AIR) {
            world.setBlock(x, y + 1, z, state.with(Properties.HALF, BlockHalf.TOP).getId(), false);
        }
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        BlockHalf half = state.get(Properties.HALF);

        /* Untere Hälfte braucht eine feste Stütze darunter. */
        if (half == BlockHalf.BOTTOM && !Blocks.getState(world.getBlock(x, y - 1, z)).isSolid()) {
            return Blocks.getState(Blocks.AIR);
        }

        int otherY = half == BlockHalf.BOTTOM ? y + 1 : y - 1;
        BlockHalf needed = half == BlockHalf.BOTTOM ? BlockHalf.TOP : BlockHalf.BOTTOM;

        BlockState other = Blocks.getState(world.getBlock(x, otherY, z));
        if (other.getBlock() != state.getBlock() || other.get(Properties.HALF) != needed) {
            return Blocks.getState(Blocks.AIR);   // andere Hälfte weg -> selbst entfernen
        }
        return state;
    }
}
