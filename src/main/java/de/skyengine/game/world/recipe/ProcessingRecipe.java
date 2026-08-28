package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record ProcessingRecipe(Identifier id, Identifier recipeType, List<CountedIngredient> inputs,
                               List<ItemStack> outputs, int duration, int priority, long loadOrder) {
    public record CountedIngredient(Ingredient ingredient, int count) {
        public CountedIngredient { if (count < 1) throw new IllegalArgumentException("count < 1"); }
    }

    public ProcessingRecipe {
        inputs = List.copyOf(inputs);
        outputs = outputs.stream().map(ItemStack::copy).toList();
        if (inputs.isEmpty() || outputs.isEmpty() || duration < 1) throw new IllegalArgumentException("Ungueltiges Maschinenrezept");
    }

    public List<ItemStack> copyOutputs() { return this.outputs.stream().map(ItemStack::copy).toList(); }

    public boolean matches(List<ItemStack> stacks) {
        Map<Item, Integer> available = new HashMap<>();
        for (ItemStack stack : stacks) if (stack != null && !stack.isEmpty()) {
            available.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<CountedIngredient> sorted = new ArrayList<>(this.inputs);
        sorted.sort(java.util.Comparator.comparingInt(value -> value.ingredient().acceptedItems().size()));
        return assign(sorted, 0, available);
    }

    private static boolean assign(List<CountedIngredient> ingredients, int index, Map<Item, Integer> available) {
        if (index == ingredients.size()) return true;
        CountedIngredient counted = ingredients.get(index);
        for (Item item : counted.ingredient().acceptedItems()) {
            int have = available.getOrDefault(item, 0);
            if (have < counted.count()) continue;
            available.put(item, have - counted.count());
            if (assign(ingredients, index + 1, available)) return true;
            available.put(item, have);
        }
        return false;
    }
}
