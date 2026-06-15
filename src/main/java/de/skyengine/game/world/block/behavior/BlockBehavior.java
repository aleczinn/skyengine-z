package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Komponierbares Block-Verhalten (Komposition statt Vererbung). Ein Block kombiniert
 * beliebig viele Behaviors; jeder Hook transformiert den State. Default = no-op.
 */
public interface BlockBehavior {

    /** Beim Platzieren: liefert den anzulegenden State (Facing, Slab-Hälfte, ...). */
    default BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state;
    }

    /** Nach Nachbaränderung: liefert den ggf. angepassten State (Verbindungen, Ecken). */
    default BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        return state;
    }

    /** Rechtsklick auf den Block. true = verbraucht (kein Platzieren). Default: ignoriert. */
    default boolean onUse(World world, int x, int y, int z, BlockState state) {
        return false;
    }
}
