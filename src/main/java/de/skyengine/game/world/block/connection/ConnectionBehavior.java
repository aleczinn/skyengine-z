package de.skyengine.game.world.block.connection;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.BlockBehavior;
import de.skyengine.game.world.block.behavior.PlacementContext;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Berechnet die Verbindungs-Booleans (NORTH/EAST/SOUTH/WEST[/UP/DOWN]) bei Platzierung
 * und Nachbar-Update aus dem {@link ConnectionComponent}. Modell (multipart-Blockstate)
 * und Shape lesen anschließend dieselben Properties.
 */
public final class ConnectionBehavior implements BlockBehavior {

    private final ConnectionComponent component;

    public ConnectionBehavior(ConnectionComponent component) {
        this.component = component;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return compute(ctx.world(), ctx.x(), ctx.y(), ctx.z(), state);
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        return compute(world, x, y, z, state);
    }

    private BlockState compute(Dimension world, int x, int y, int z, BlockState state) {
        for (Direction d : this.component.axes()) {
            BlockState neighbor = Blocks.getState(world.getBlock(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ()));
            boolean connected = this.component.rule().connects(world, x, y, z, d, state, neighbor);
            state = state.with(Properties.connection(d), connected);
        }
        return state;
    }
}
