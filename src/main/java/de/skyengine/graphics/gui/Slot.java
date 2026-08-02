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
    /** Bereich für Quickmove/Shift-Klick — siehe {@link SlotGroup}. */
    public final SlotGroup group;

    public Slot(ItemStorage storage, int index, int x, int y, SlotGroup group) {
        this.storage = storage;
        this.index = index;
        this.x = x;
        this.y = y;
        this.group = group;
    }

    public ItemStack get() {
        return this.storage.get(this.index);
    }

    public void set(ItemStack stack) {
        this.storage.set(this.index, stack);
    }

    /**
     * true, wenn (px, py) in der Trefferfläche liegt: die {@code size}×{@code size}-Innenfläche,
     * um {@code pad} nach allen Seiten erweitert. Bei Rasterabstand 18 und Größe 16 schließt
     * {@code pad = 1} die 2 px breite tote Zone zwischen zwei Slots — die Flächen grenzen dann
     * lückenlos aneinander, ohne sich zu überlappen (MC macht es in {@code isHovering} genauso).
     */
    public boolean contains(double px, double py, int size, int pad) {
        return px >= this.x - pad && px < this.x + size + pad
                && py >= this.y - pad && py < this.y + size + pad;
    }
}
