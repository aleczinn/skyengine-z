package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.HopperBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.redstone.RedstonePower;

/**
 * Trichter-Block: {@code facing} = Auslaufrichtung — die INVERTIERTE Klickfläche, Ober-
 * und Unterseite münden beide in DOWN (MC). Ein Redstone-Signal deaktiviert den Trichter
 * ({@code enabled}, invers zu POWERED); die Transfer-Logik lebt in der
 * {@link HopperBlockEntity}, das GUI kommt in der Folge-Etappe.
 */
public final class HopperBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        Direction facing = ctx.faceY() != 0 ? Direction.DOWN
                : horizontalOf(ctx.faceX(), ctx.faceZ()).opposite();
        return state.with(Properties.FACING_ALL, facing)
                .with(Properties.ENABLED, !RedstonePower.isReceiving(ctx.world(), ctx.x(), ctx.y(), ctx.z()));
    }

    private static Direction horizontalOf(int faceX, int faceZ) {
        if (faceX > 0) return Direction.EAST;
        if (faceX < 0) return Direction.WEST;
        if (faceZ > 0) return Direction.SOUTH;
        return Direction.NORTH;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        boolean enabled = !RedstonePower.isReceiving(world, x, y, z);
        if (enabled != state.get(Properties.ENABLED)) {
            return state.with(Properties.ENABLED, enabled);
        }
        return state;
    }

    /**
     * Der ENABLED-Wechsel ist eine echte Blockstate-Änderung. Vanillas Shape-Update macht
     * sie für einen auf den Trichter gerichteten Beobachter sichtbar.
     */
    @Override
    public void onStateChangedByNeighborUpdate(World world, int x, int y, int z,
                                               BlockState oldState, BlockState newState) {
        if (oldState.get(Properties.ENABLED) != newState.get(Properties.ENABLED)) {
            ObserverBehavior.notifyWatching(world, x, y, z);
        }
    }

    /** Beim Abbauen fällt der Inhalt heraus (dasselbe Muster wie die Truhe). */
    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        BlockEntity be = world.getBlockEntity(x, y, z);
        if (!(be instanceof HopperBlockEntity hopper)) return;
        ItemStorage inventory = hopper.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) continue;
            inventory.set(slot, ItemStack.EMPTY);
            world.spawnItem(x + 0.5, y + 0.5, z + 0.5, stack);
        }
    }
}
