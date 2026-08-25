package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.DispenserBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;
import de.skyengine.game.world.redstone.RedstonePower;

/** Gemeinsame Redstone-, Platzierungs- und Inventarlogik von Dispenser und Dropper. */
public final class DispenserBehavior implements BlockBehavior {

    private static final int TRIGGER_DELAY = 4;
    private final boolean dropper;

    public DispenserBehavior(boolean dropper) {
        this.dropper = dropper;
    }

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING_ALL, facingToPlayer(ctx))
                .with(Properties.TRIGGERED, false);
    }

    private static Direction facingToPlayer(PlacementContext ctx) {
        if (ctx.playerPitch() > 45) return Direction.UP;
        if (ctx.playerPitch() < -45) return Direction.DOWN;
        return Direction.fromYaw(ctx.playerYaw()).opposite();
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        /* Vanilla-Quasi-Connectivity: Dispenser und Dropper prüfen zusätzlich die Zelle darüber. */
        boolean powered = RedstonePower.isReceiving(world, x, y, z)
                || RedstonePower.isReceiving(world, x, y + 1, z);
        boolean triggered = state.get(Properties.TRIGGERED);
        if (powered && !triggered) {
            world.scheduleTick(x, y, z, TRIGGER_DELAY);
            return state.with(Properties.TRIGGERED, true);
        }
        if (!powered && triggered) return state.with(Properties.TRIGGERED, false);
        return state;
    }

    @Override
    public void scheduledTick(Dimension world, int x, int y, int z, BlockState state) {
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        if (blockEntity instanceof DispenserBlockEntity dispenser) {
            dispenser.activate(state.get(Properties.FACING_ALL), this.dropper);
        }
    }

    @Override
    public void appendDrops(LootContext context, LootSink sink) {
        BlockEntity blockEntity = context.world().getBlockEntity(context.x(), context.y(), context.z());
        if (!(blockEntity instanceof DispenserBlockEntity dispenser)) return;
        ItemStorage inventory = dispenser.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) continue;
            inventory.set(slot, ItemStack.EMPTY);
            sink.accept(stack, context.x(), context.y(), context.z());
        }
    }
}
