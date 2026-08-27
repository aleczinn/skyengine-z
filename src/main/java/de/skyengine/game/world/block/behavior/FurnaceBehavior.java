package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.FurnaceBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;

/** Laesst beim Abbau eines Ofens dessen drei persistente Slots fallen. */
public final class FurnaceBehavior implements BlockBehavior {
    @Override
    public void appendDrops(LootContext context, LootSink sink) {
        BlockEntity entity = context.world().getBlockEntity(context.x(), context.y(), context.z());
        if (!(entity instanceof FurnaceBlockEntity furnace)) return;
        ItemStorage inventory = furnace.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.extract(slot, Integer.MAX_VALUE);
            if (!stack.isEmpty()) sink.accept(stack, context.x(), context.y(), context.z());
        }
    }
}
