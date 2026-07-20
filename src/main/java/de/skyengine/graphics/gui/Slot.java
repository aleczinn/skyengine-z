package de.skyengine.graphics.gui;

import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;

/**
 * Ein GUI-Slot: Verweis auf ein {@link ItemStorage} + Slot-Index, dazu die Pixelposition (oben links)
 * im GuiScreen. So teilen sich Truhe und Spielerinventar dieselbe Slot-Logik.
 */
public final class Slot {

    public final ItemStorage storage;
    public final int index;
    public final int x;
    public final int y;

    public Slot(ItemStorage storage, int index, int x, int y) {
        this.storage = storage;
        this.index = index;
        this.x = x;
        this.y = y;
    }

    public ItemStack get() {
        return this.storage.get(this.index);
    }

    public void set(ItemStack stack) {
        this.storage.set(this.index, stack);
    }

    /** true, wenn (px, py) innerhalb der 16×16-Innenfläche dieses Slots liegt. */
    public boolean contains(double px, double py, int size) {
        return px >= this.x && px < this.x + size && py >= this.y && py < this.y + size;
    }
}
