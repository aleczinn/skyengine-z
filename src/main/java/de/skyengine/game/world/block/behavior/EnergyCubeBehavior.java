package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.entity.Capability;
import de.skyengine.game.world.block.entity.EnergyCubeBlockEntity;
import de.skyengine.game.world.block.entity.EnergyStorage;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;
import de.skyengine.game.world.item.TooltipContext;

import java.util.Map;
import java.util.Optional;

/** Drops the cube itself together with its portable RF and side configuration. */
public final class EnergyCubeBehavior implements BlockBehavior {
    @Override @SuppressWarnings("unchecked")
    public <C> Optional<C> getItemCapability(Capability<C> capability, ItemStack stack) {
        if (capability != Capabilities.ENERGY || stack == null || stack.isEmpty()) return Optional.empty();
        return Optional.of((C) new StackEnergyStorage(stack));
    }

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
        ItemStorage inventory = cube.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack contents = inventory.get(slot).copy();
            if (!contents.isEmpty()) sink.accept(contents, context.x(), context.y(), context.z());
        }
    }

    private static final class StackEnergyStorage implements EnergyStorage {
        private final ItemStack stack;
        private StackEnergyStorage(ItemStack stack) { this.stack = stack; }
        private long energy() {
            DataTag data = this.stack.getCustomData();
            return data == null ? 0 : Math.clamp(data.getLong("energy", 0), 0, EnergyCubeBlockEntity.CAPACITY);
        }
        private void energy(long value) {
            DataTag data = this.stack.getCustomData();
            if (data == null) data = new DataTag();
            data.putLong("energy", Math.clamp(value, 0, EnergyCubeBlockEntity.CAPACITY));
            this.stack.setCustomData(data);
        }
        @Override public long receive(long amount, boolean simulate) {
            long accepted = Math.min(Math.max(0, amount), Math.min(EnergyCubeBlockEntity.TRANSFER_RATE,
                    EnergyCubeBlockEntity.CAPACITY - energy()));
            if (!simulate && accepted > 0) energy(energy() + accepted);
            return accepted;
        }
        @Override public long extract(long amount, boolean simulate) {
            long extracted = Math.min(Math.max(0, amount), Math.min(EnergyCubeBlockEntity.TRANSFER_RATE, energy()));
            if (!simulate && extracted > 0) energy(energy() - extracted);
            return extracted;
        }
        @Override public long getEnergy() { return energy(); }
        @Override public long getCapacity() { return EnergyCubeBlockEntity.CAPACITY; }
        @Override public long getMaxReceive() { return EnergyCubeBlockEntity.TRANSFER_RATE; }
        @Override public long getMaxExtract() { return EnergyCubeBlockEntity.TRANSFER_RATE; }
    }
}
