package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Hebel: Rechtsklick schaltet um — kein geplanter Tick, der Zustand bleibt, bis wieder
 * geklickt wird. Träger- und Ausricht-Logik wie beim Knopf (Komposition mit
 * {@link AttachBehavior}); Signal ebenfalls: schwach 15 in alle Richtungen, stark 15
 * in den Träger (dessen Nachbarn kriegen den zweiten Ring).
 */
public final class LeverBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        BlockState placed = state.with(Properties.POWERED, false);
        /* Wie beim Knopf: an Boden/Decke fehlt nur die Blickrichtung. */
        if (placed.get(Properties.ATTACH) == AttachFace.WALL) return placed;
        return placed.with(Properties.FACING, Direction.fromYaw(ctx.playerYaw()).opposite());
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        boolean powered = !state.get(Properties.POWERED);
        /* true = Nachbar-Update, sonst erführe die Tür nebenan nichts davon. */
        world.setBlock(x, y, z, state.with(Properties.POWERED, powered).getId(), true);
        ButtonBehavior.notifyStrongTarget(world, x, y, z, state);
        return true;
    }

    @Override
    public void onRemoved(World world, int x, int y, int z,
                          BlockState oldState, BlockState newState) {
        if (oldState.get(Properties.POWERED)) {
            ButtonBehavior.notifyStrongTarget(world, x, y, z, oldState);
        }
    }

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED) ? 15 : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED) && side == ButtonBehavior.supportDirection(state) ? 15 : 0;
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return true;
    }

    @Override
    public boolean isRedstoneSignalSource() {
        return true;
    }
}
