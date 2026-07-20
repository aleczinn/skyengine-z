package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Basis aller Slot-Container-Screens (Truhe, Spielerinventar, künftige Maschinen-GUIs):
 * gemeinsame Slot-Liste, getragener Stapel am Cursor, Klick-Tauschlogik und das Zurücklegen
 * beim Schließen. Die Klick-Logik ist über {@link #onSlotClick} überschreibbar — dort docken
 * später Stack-Regeln/Maus-Shortcuts (Phase 2) an, ohne die Screens anzufassen.
 */
public abstract class GuiContainer extends GuiScreen {

    protected static final int COLS = 9, SLOT = 16, STEP = 18;

    protected final List<Slot> slots = new ArrayList<>();
    protected ItemStack carried = ItemStack.EMPTY;

    /** Ziele fürs Zurücklegen des getragenen Stapels beim Schließen (in Reihenfolge). */
    private final ItemStorage[] returnCarriedTo;

    protected GuiContainer(ItemStorage... returnCarriedTo) {
        super(null);
        this.returnCarriedTo = returnCarriedTo;
    }

    @Override
    public boolean closesOnInventoryKey() {
        return true;
    }

    protected Slot slotAt(double mx, double my) {
        for (Slot s : this.slots) {
            if (s.contains(mx, my, SLOT)) return s;
        }
        return null;
    }

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot == null) return false;
        this.onSlotClick(slot, button);
        return true;
    }

    /**
     * Klick auf einen Slot: klassische Carried-Tauschlogik (aufnehmen, ablegen, stapeln,
     * tauschen). {@code button} wird aktuell ignoriert (Rechtsklick-Halbieren folgt in Phase 2).
     */
    protected void onSlotClick(Slot slot, int button) {
        ItemStack slotStack = slot.get();
        if (this.carried.isEmpty()) {
            this.carried = slotStack;
            slot.set(ItemStack.EMPTY);
        } else if (slotStack.isEmpty()) {
            slot.set(this.carried);
            this.carried = ItemStack.EMPTY;
        } else if (slotStack.canStackWith(this.carried)) {
            int space = slotStack.getMaxStackSize() - slotStack.getCount();
            int move = Math.min(space, this.carried.getCount());
            slotStack.setCount(slotStack.getCount() + move);
            this.carried.setCount(this.carried.getCount() - move);
            if (this.carried.getCount() <= 0) this.carried = ItemStack.EMPTY;
        } else {
            slot.set(this.carried);
            this.carried = slotStack;
        }
    }

    /** Hover-Highlight des Slots unter der Maus (im Sprite-Pass der Subklasse aufrufen). */
    protected void drawSlotHover(GuiManager gui, double mouseX, double mouseY) {
        Slot hover = this.slotAt(mouseX, mouseY);
        if (hover != null) {
            gui.sprites().drawRect(hover.x, hover.y, SLOT, SLOT, 1f, 1f, 1f, 0.35f);
        }
    }

    /** Alle Slot-Icons + den getragenen Stapel am Cursor zeichnen (eigener Icon-Pass). */
    protected void drawSlotIcons(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        gui.icons().begin(vW, vH);
        for (Slot s : this.slots) {
            ItemStack st = s.get();
            if (!st.isEmpty()) gui.icons().drawIcon(st, s.x + SLOT / 2f, s.y + SLOT / 2f, SLOT, vH);
        }
        if (!this.carried.isEmpty()) {
            gui.icons().drawIcon(this.carried, (float) mouseX, (float) mouseY, SLOT, vH);
        }
        gui.icons().end();
    }

    @Override
    public void onClose() {
        if (this.carried.isEmpty()) return;
        ItemStack rest = this.carried;
        for (ItemStorage storage : this.returnCarriedTo) {
            rest = storage.insert(rest);
            if (rest.isEmpty()) break;
        }
        this.carried = ItemStack.EMPTY; // notfalls verworfen (alle Ziele voll)
    }
}
