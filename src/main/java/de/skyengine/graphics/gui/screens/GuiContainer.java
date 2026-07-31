package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.GameContainer;
import de.skyengine.game.Gamemode;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.StackText;
import de.skyengine.graphics.gui.Tooltip;
import de.skyengine.graphics.gui.text.RichText;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
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

    /* --- Quickmove (Shift-Klick, Mausrad) --- */

    /**
     * Zielreihenfolge eines Quickmove aus {@code from} (Vorbild MC
     * {@code AbstractContainerMenu.quickMoveStack}). Ein Screen ohne {@link SlotGroup#CONTAINER}
     * (das reine Spielerinventar) schiebt zwischen Hauptinventar und Hotbar hin und her.
     *
     * <p>Aus dem Container heraus läuft die Liste RÜCKWÄRTS und die Hotbar zuerst — das ist MCs
     * {@code moveItemStackTo(..., reverseDirection = true)}.
     */
    protected List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.CONTAINER) {
            List<Slot> targets = new ArrayList<>(this.slotsOf(SlotGroup.HOTBAR));
            targets.addAll(this.slotsOf(SlotGroup.INVENTORY));
            Collections.reverse(targets);
            return targets;
        }
        List<Slot> container = this.slotsOf(SlotGroup.CONTAINER);
        if (!container.isEmpty()) return container;
        return this.slotsOf(from == SlotGroup.HOTBAR ? SlotGroup.INVENTORY : SlotGroup.HOTBAR);
    }

    /** Alle Slots einer Gruppe in Layout-Reihenfolge. */
    protected List<Slot> slotsOf(SlotGroup group) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : this.slots) {
            if (s.group == group) out.add(s);
        }
        return out;
    }

    /**
     * Schiebt bis zu {@code amount} Items aus {@code from} in den anderen Bereich; liefert die
     * tatsächlich verschobene Menge. Der Quellslot wird geleert, sobald nichts mehr übrig ist.
     */
    protected int quickMove(Slot from, int amount) {
        ItemStack stack = from.get();
        if (stack.isEmpty() || amount <= 0) return 0;
        ItemStack moving = stack.split(amount);
        int wanted = moving.getCount();

        this.moveInto(moving, this.quickMoveTargets(from.group));

        /* Nicht untergebrachten Rest zurücklegen — split hat ihn schon abgezogen. */
        if (!moving.isEmpty()) stack.setCount(stack.getCount() + moving.getCount());
        if (stack.isEmpty()) from.set(ItemStack.EMPTY);
        return wanted - moving.getCount();
    }

    /**
     * Verteilt {@code stack} auf {@code targets} (Vorbild MC {@code moveItemStackTo}): erst in
     * gleiche Stapel auffüllen, dann in leere Slots. Beide Pässe sehen die GANZE Liste — sonst
     * belegt ein Item einen leeren Slot, obwohl weiter hinten noch ein passender Teilstapel
     * Platz hätte. {@code stack} wird dabei heruntergezählt.
     */
    protected void moveInto(ItemStack stack, List<Slot> targets) {
        for (Slot target : targets) {
            if (stack.isEmpty()) return;
            ItemStack existing = target.get();
            if (!existing.canStackWith(stack)) continue;
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) continue;
            existing.setCount(existing.getCount() + stack.split(space).getCount());
        }
        for (Slot target : targets) {
            if (stack.isEmpty()) return;
            if (!target.get().isEmpty()) continue;
            target.set(stack.split(stack.getMaxStackSize()));
        }
    }

    /* --- Klicks --- */

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot != null) {
            this.onSlotClick(slot, button, SkyEngine.get().getInput().isShiftDown());
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
     * Klick auf einen Slot (Vorbild MC {@code AbstractContainerMenu.doClick}): Shift schiebt in
     * den anderen Bereich, links nimmt/legt den ganzen Stapel, rechts halbiert bzw. legt genau
     * ein Item ab, die Mitte klont im Creative.
     */
    protected void onSlotClick(Slot slot, int button, boolean shift) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            this.cloneInCreative(slot);
            return;
        }
        /* Maus 4/5 kommen wegen capturesMouse() mit an — sie dürfen NICHT wie ein Linksklick wirken. */
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;

        if (shift) {
            ItemStack stack = slot.get();
            if (!stack.isEmpty()) this.quickMove(slot, stack.getCount());
            return;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            this.rightClick(slot);
            return;
        }

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

    /** Rechtsklick: mit leerer Hand die Hälfte aufnehmen (aufgerundet), sonst 1 Item ablegen. */
    private void rightClick(Slot slot) {
        ItemStack slotStack = slot.get();
        if (this.carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            this.carried = slotStack.split((slotStack.getCount() + 1) / 2);
            if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
            return;
        }
        if (slotStack.isEmpty()) {
            slot.set(this.carried.split(1));
        } else if (slotStack.canStackWith(this.carried)
                && slotStack.getCount() < slotStack.getMaxStackSize()) {
            slotStack.setCount(slotStack.getCount() + this.carried.split(1).getCount());
        } else {
            /* Fremder Stapel: wie beim Linksklick tauschen. */
            slot.set(this.carried);
            this.carried = slotStack;
            return;
        }
        if (this.carried.isEmpty()) this.carried = ItemStack.EMPTY;
    }

    /** Mittelklick im Creative: voller Stapel in die Hand (MC {@code ClickType.CLONE}). */
    private void cloneInCreative(Slot slot) {
        if (!this.carried.isEmpty() || slot.get().isEmpty()) return;
        EntityPlayer player = SkyEngine.get().getGame().getPlayer();
        if (player == null || player.getGamemode() != Gamemode.CREATIVE) return;
        this.carried = slot.get().copy();
        this.carried.setCount(this.carried.getMaxStackSize());
    }

    /**
     * Container-Screens beanspruchen alle Maustasten — sonst filtert der {@link GuiManager} den
     * Mittelklick (Klonen im Creative) weg. Maus 4/5 kommen dadurch mit an und werden in
     * {@link #onSlotClick} ausdrücklich verworfen.
     */
    @Override
    public boolean capturesMouse() {
        return true;
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
