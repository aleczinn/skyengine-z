package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;

/** Setzt die Slab-Hälfte aus getroffener Fläche und relativem Treffer-Y. */
public final class SlabPlacementBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        boolean top = ctx.faceY() < 0 || (ctx.faceY() == 0 && ctx.hitY() > 0.5);
        return state.with(Properties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }
}
