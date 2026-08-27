package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** O(1)-Lookup fuer vorkompilierte Brennstoffwerte. */
public final class FuelRegistry {

    public record Fuel(Identifier id, Identifier type, Ingredient ingredient, int burnTime,
                       int priority, long loadOrder) {}

    private record Key(Identifier type, Item item) {}
    private final Map<Key, Fuel> values = new HashMap<>();

    void add(Fuel fuel) {
        for (Item item : fuel.ingredient().acceptedItems()) {
            Key key = new Key(fuel.type(), item);
            Fuel current = this.values.get(key);
            if (current == null || RecipeManager.compare(fuel.priority(), fuel.loadOrder(), fuel.id(),
                    current.priority(), current.loadOrder(), current.id()) < 0) {
                this.values.put(key, fuel);
            }
        }
    }

    public int burnTime(Identifier type, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        Fuel fuel = this.values.get(new Key(type, stack.getItem()));
        return fuel == null ? 0 : fuel.burnTime();
    }
}
