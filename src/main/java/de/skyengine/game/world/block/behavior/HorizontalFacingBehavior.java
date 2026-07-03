package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Setzt beim Platzieren die horizontale {@link Properties#FACING}-Ausrichtung so, dass die
 * Vorderseite zum platzierenden Spieler zeigt (Truhe, Ofen, ...). Identische Blickrichtungs-
 * konvention wie die Tür ({@link DoorBehavior}): Blickrichtung invertiert = Vorderseite zum Spieler.
 *
 * <p>Erwartet, dass der Block das FACING-Property trägt (über das JSON-Flag {@code "facing": true},
 * angewendet in {@link de.skyengine.game.world.block.archetype.ArchetypeBlockFactory}).
 */
public final class HorizontalFacingBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        Direction facing = Direction.fromYaw(ctx.playerYaw()).opposite();
        return state.with(Properties.FACING, facing);
    }
}
