package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.entity.CoalGeneratorBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;

/** Drops the generator's fuel inventory; stored RF is intentionally discarded. */
public final class CoalGeneratorBehavior implements BlockBehavior {
    @Override public void appendDrops(LootContext context, LootSink sink) {
        if (!(context.world().getBlockEntity(context.x(), context.y(), context.z())
                instanceof CoalGeneratorBlockEntity generator)) return;
        ItemStorage inventory = generator.getInventory();
        ItemStack fuel = inventory.get(0).copy();
        if (!fuel.isEmpty()) sink.accept(fuel, context.x(), context.y(), context.z());
    }
}
