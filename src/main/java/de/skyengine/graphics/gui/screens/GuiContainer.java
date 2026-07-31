package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.GameContainer;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.StackText;
import de.skyengine.graphics.gui.Tooltip;
import de.skyengine.graphics.gui.text.RichText;
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

    /**
     * Liegt (mx,my) im Fenster-Rechteck des Screens? Vorbild MC
     * {@code AbstractContainerScreen.hasClickedOutside} — nur ein Klick DANEBEN wirft den
     * getragenen Stapel aus, auf dem Fensterhintergrund passiert nichts. Default: alles „drin"
     * (= wirft nie), damit ein neuer Container-Screen nichts Unerwartetes tut.
     */
    protected boolean isInsideWindow(double mx, double my) {
        return true;
    }

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot != null) {
            this.onSlotClick(slot, button);
            return true;
        }
        /* Klick neben das Fenster wirft den getragenen Stapel aus: links alles, rechts einzeln. */
        if (!this.carried.isEmpty() && !this.isInsideWindow(mouseX, mouseY)) {
            this.throwFromCarried(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? 1 : this.carried.getCount());
            return true;
        }
        return false;
    }

    /**
     * Drop-Taste (Default Q) wie in Minecraft: mit belegtem Cursor wirft sie von IHM, sonst vom
     * Slot unter der Maus — mit STRG jeweils den ganzen Stapel. Die Mausposition kommt aus dem
     * {@link GuiManager}, {@code keyPressed} bekommt sie nicht übergeben.
     */
    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        if (super.keyPressed(gui, key)) return true;   // fokussiertes Widget + Default-ESC zuerst
        if (key != GameSettings.get().key(KeyBindings.DROP)) return false;

        boolean fullStack = SkyEngine.get().getInput().isCtrlDown();
        if (!this.carried.isEmpty()) {
            this.throwFromCarried(fullStack ? this.carried.getCount() : 1);
            return true;
        }
        Slot slot = this.slotAt(gui.mouseX(), gui.mouseY());
        if (slot != null && !slot.get().isEmpty()) {
            throwOut(slot.storage.extract(slot.index, fullStack ? slot.get().getCount() : 1));
        }
        return true;
    }

    /** Wirft {@code amount} vom getragenen Stapel aus. */
    private void throwFromCarried(int amount) {
        throwOut(this.carried.split(amount));
        if (this.carried.isEmpty()) this.carried = ItemStack.EMPTY;
    }

    /** Wirft einen Stapel in die Welt (ohne Welt — Hauptmenü — passiert nichts). */
    private static void throwOut(ItemStack stack) {
        if (!stack.isEmpty()) SkyEngine.get().getGame().dropFromGui(stack);
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

    /** Alle Slot-Icons + den getragenen Stapel am Cursor zeichnen (Icon-Pass + Zahlen-Pass). */
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

        /* Stack-Zahlen NACH dem Icon-Pass (Depth aus -> Text liegt über den 3D-Icons),
           unten rechts im Slot wie in Minecraft. */
        gui.font().begin(vW, vH);
        for (Slot s : this.slots) {
            StackText.draw(gui, s.get(), s.x, s.y, SLOT);
        }
        if (!this.carried.isEmpty()) {
            StackText.draw(gui, this.carried,
                    (float) mouseX - SLOT / 2f, (float) mouseY - SLOT / 2f, SLOT);
        }
        gui.font().end();
    }

    private static final Color4 TOOLTIP_GRAY = new Color4(0.67f, 0.67f, 0.67f, 1f);

    /** Zeilen des Tooltips eines Stacks — Andockpunkt für spätere Stats (Haltbarkeit etc.). */
    protected List<RichText> tooltipLines(ItemStack stack) {
        return List.of(
                RichText.plain(stack.getDisplayName(), Colors.WHITE),
                RichText.plain(stack.getItem().getId().toString(), TOOLTIP_GRAY));
    }

    /**
     * Tooltip für den Slot unter der Maus, GANZ am Ende von {@code render()} aufrufen
     * (eigene Sprite-/Font-Pässe — muss über Icons, Stack-Zahlen und Carried liegen).
     * Wie MC nur mit leerer Hand: mit getragenem Stapel verdeckt der Cursor den Slot ohnehin.
     */
    protected void drawTooltip(GuiManager gui, double mouseX, double mouseY) {
        if (!this.carried.isEmpty()) return;
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot == null || slot.get().isEmpty()) return;
        Tooltip.draw(gui, this.tooltipLines(slot.get()), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.carried.isEmpty()) return;
        GameContainer game = SkyEngine.get().getGame();
        if (game.getWorld() != null) {
            game.dropFromGui(this.carried);  // wie in MC: der getragene Stapel fliegt raus
        } else {
            /* Ohne Welt gibt es kein Wurfziel (Screen-Wechsel Richtung Hauptmenü) — dann
               zurücklegen, notfalls verworfen (alle Ziele voll). */
            ItemStack rest = this.carried;
            for (ItemStorage storage : this.returnCarriedTo) {
                rest = storage.insert(rest);
                if (rest.isEmpty()) break;
            }
        }
        this.carried = ItemStack.EMPTY;
    }
}
