package de.skyengine.game.world.recipe;

import de.skyengine.game.world.item.ItemStack;

import java.util.Arrays;

record NormalizedGrid(int width, int height, ItemStack[] stacks, String occupancy) implements CraftingGrid {
    static NormalizedGrid of(CraftingGrid source) {
        int minX = source.width(), minY = source.height(), maxX = -1, maxY = -1;
        for (int y = 0; y < source.height(); y++) for (int x = 0; x < source.width(); x++) {
            if (source.get(x, y).isEmpty()) continue;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        if (maxX < 0) return new NormalizedGrid(0, 0, new ItemStack[0], "");
        int width = maxX - minX + 1, height = maxY - minY + 1;
        ItemStack[] stacks = new ItemStack[width * height];
        Arrays.fill(stacks, ItemStack.EMPTY);
        StringBuilder mask = new StringBuilder(stacks.length);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            ItemStack stack = source.get(minX + x, minY + y);
            stacks[y * width + x] = stack;
            mask.append(stack.isEmpty() ? '0' : '1');
        }
        return new NormalizedGrid(width, height, stacks, mask.toString());
    }

    @Override public ItemStack get(int x, int y) { return this.stacks[y * this.width + x]; }
}
