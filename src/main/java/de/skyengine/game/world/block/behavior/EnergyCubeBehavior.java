package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.entity.EnergyCubeBlockEntity;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;
import de.skyengine.game.world.item.TooltipContext;

import java.util.Map;

/** Drops the cube itself together with its portable RF and side configuration. */
public final class EnergyCubeBehavior implements BlockBehavior {
    @Override public void appendTooltipVariables(ItemStack stack, TooltipContext context,
                                                 Map<String, String> variables) {
        DataTag data = stack.getCustomData();
        long energy = data == null ? 0 : Math.clamp(data.getLong("energy", 0),
                0, EnergyCubeBlockEntity.CAPACITY);
        variables.put("energy", Long.toString(energy));
        variables.put("capacity", Long.toString(EnergyCubeBlockEntity.CAPACITY));
        variables.put("rate", Long.toString(EnergyCubeBlockEntity.TRANSFER_RATE));
    }

    @Override public void appendDrops(LootContext context, LootSink sink) {
        if (!(context.world().getBlockEntity(context.x(), context.y(), context.z())
                instanceof EnergyCubeBlockEntity cube)) return;
        Item item = Items.forBlock(context.state().getBlock());
        if (item == null) return;
        ItemStack stack = new ItemStack(item, 1);
        DataTag portable = new DataTag();
        cube.savePortable(portable);
        stack.setCustomData(portable);
        sink.accept(stack, context.x(), context.y(), context.z());
    }
}
