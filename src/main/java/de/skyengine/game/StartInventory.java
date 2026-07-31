package de.skyengine.game;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;

/**
 * Befüllt das Startinventar frisch erstellter Welten (Testblöcke — ohne Crafting/Creative-Menü
 * der einzige Weg, diese Items in die Hand zu bekommen, s. CLAUDE.md). Reine Datenbefüllung,
 * aus dem GameContainer herausgelöst; neue Testblöcke hier eintragen.
 */
public final class StartInventory {

    private StartInventory() {
    }

    public static void fill(SimpleItemStorage inventory) {
        set(inventory, 0, "skyengine:tuff", 1);
        set(inventory, 1, "skyengine:coarse_dirt", 1);
        set(inventory, 2, "skyengine:red_mushroom", 1);
        set(inventory, 3, "skyengine:apple", 16);
        set(inventory, 4, "skyengine:bread", 16);
        set(inventory, 5, "skyengine:chest", 1);
        set(inventory, 6, "skyengine:water_bucket", 1);
        set(inventory, 7, "skyengine:lava_bucket", 1);
        set(inventory, 8, "skyengine:torch", 64);
        set(inventory, 9, "skyengine:iron_bars", 1);
        set(inventory, 10, "skyengine:tnt", 64);
        /* Bewegungs-Testblöcke (friction/speed_factor/jump_factor) — ohne Creative-Menü
           sonst nicht erreichbar. */
        set(inventory, 11, "skyengine:ice", 64);
        set(inventory, 12, "skyengine:blue_ice", 64);
        set(inventory, 13, "skyengine:soul_sand", 64);
        set(inventory, 14, "skyengine:honey_block", 64);
        set(inventory, 15, "skyengine:slime_block", 64);
        set(inventory, 16, "skyengine:end_stone", 64);
    }

    private static void set(SimpleItemStorage inventory, int slot, String itemId, int count) {
        Item item = Items.get(Identifier.of(itemId));
        if (item != null) inventory.set(slot, new ItemStack(item, count));
    }
}
