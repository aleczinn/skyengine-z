package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Die Stützregel einer zwei Blöcke hohen Pflanze (tall_grass): Nur die UNTERE Hälfte braucht
 * einen festen Block darunter — die obere steht auf der nicht-soliden unteren und würde sich
 * mit {@link PlantBehavior} sofort selbst entfernen.
 *
 * <p>Das Zweiteilige selbst (obere Hälfte setzen, Platzprüfung, Selbstentfernen bei fehlender
 * Gegenhälfte) steckt in der {@code parts}-Sektion der Block-JSON → {@link PartsBehavior}.
 */
public final class TallPlantBehavior implements BlockBehavior {

    @Override
    public boolean canPlace(PlacementContext ctx, BlockState state) {
        return Blocks.getState(ctx.world().getBlock(ctx.x(), ctx.y() - 1, ctx.z())).isSolid();
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        if (state.get(Properties.HALF) == BlockHalf.BOTTOM
                && !Blocks.getState(world.getBlock(x, y - 1, z)).isSolid()) {
            return Blocks.getState(Blocks.AIR);
        }
        return state;
    }
}
