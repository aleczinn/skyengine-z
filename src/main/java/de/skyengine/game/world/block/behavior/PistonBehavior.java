package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Kolben-Basis. Platzierung wie MC: der Kolben schaut den Spieler an — steil nach unten
 * geschaut zeigt er nach OBEN, steil nach oben geschaut nach UNTEN, sonst horizontal
 * entgegen der Blickrichtung. Die Schub-Mechanik (Zustandsmaschine über scheduledTick)
 * folgt im Mechanik-Commit.
 */
public final class PistonBehavior implements BlockBehavior {

    private final boolean sticky;

    public PistonBehavior(boolean sticky) {
        this.sticky = sticky;
    }

    public boolean isSticky() {
        return this.sticky;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING_ALL, facingToPlayer(ctx))
                .with(Properties.EXTENDED, false);
    }

    /** 6-Richtungs-Facing zum Spieler (Engine-Konvention: positiver Pitch = runterschauen). */
    static Direction facingToPlayer(PlacementContext ctx) {
        if (ctx.playerPitch() > 45) return Direction.UP;
        if (ctx.playerPitch() < -45) return Direction.DOWN;
        return Direction.fromYaw(ctx.playerYaw()).opposite();
    }
}
