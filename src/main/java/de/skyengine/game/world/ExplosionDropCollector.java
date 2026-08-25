package de.skyengine.game.world;

import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.loot.LootSink;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Sammelt Explosionsloot vor dem Spawn in Vanilla-kompatiblen 16er-Stapeln. */
public final class ExplosionDropCollector implements LootSink {

    private final IdentityHashMap<Item, ArrayList<DropSlot>> byItem = new IdentityHashMap<>();
    private final ArrayList<DropSlot> ordered = new ArrayList<>();

    @Override
    public void accept(ItemStack input, int x, int y, int z) {
        if (input == null || input.isEmpty()) return;
        ArrayList<DropSlot> sameItem = byItem.computeIfAbsent(input.getItem(), ignored -> new ArrayList<>());
        int remaining = input.getCount();
        for (DropSlot slot : sameItem) {
            if (remaining == 0) break;
            if (!slot.stack.canStackWith(input) || slot.stack.getCount() >= 16) continue;
            int moved = Math.min(remaining, 16 - slot.stack.getCount());
            slot.stack.setCount(slot.stack.getCount() + moved);
            remaining -= moved;
        }
        while (remaining > 0) {
            ItemStack stack = input.copy();
            stack.setCount(Math.min(16, remaining));
            DropSlot slot = new DropSlot(stack, x, y, z);
            sameItem.add(slot);
            ordered.add(slot);
            remaining -= stack.getCount();
        }
    }

    public int entityCount() { return this.ordered.size(); }

    public void spawn(Dimension world) {
        for (DropSlot slot : ordered) {
            world.spawnItem(slot.x + 0.5, slot.y + 0.5, slot.z + 0.5, slot.stack);
        }
    }

    private record DropSlot(ItemStack stack, int x, int y, int z) {}
}
