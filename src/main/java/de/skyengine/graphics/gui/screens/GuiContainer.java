package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.GameContainer;
import de.skyengine.game.Gamemode;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.item.TooltipContext;
import de.skyengine.graphics.DebugFlags;
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

    /**
     * Erweiterung der Trefferfläche nach allen Seiten. Ohne sie bleiben bei Raster 18 und
     * Slotgröße 16 zwei Pixel tote Zone zwischen zwei Slots, in denen ein Ablegen ins Leere
     * geht. Mit 1 grenzen die Flächen lückenlos aneinander (und überlappen nicht) — MC macht
     * es in {@code isHovering} genauso.
     */
    protected static final int HIT_PAD = 1;

    protected final List<Slot> slots = new ArrayList<>();
    protected ItemStack carried = ItemStack.EMPTY;

    /** Ziele fürs Zurücklegen des getragenen Stapels beim Schließen (in Reihenfolge). */
    private final ItemStorage[] returnCarriedTo;

    /** Zeitfenster für den Doppelklick (wie in {@code GuiSelectWorld}). */
    private static final long DOUBLE_CLICK_MS = 400;
    private Slot lastClickSlot;
    private long lastClickTime;

    /**
     * Was ein laufender Zug mit den berührten Slots macht. Der Modus wird beim DRÜCKEN festgelegt
     * — nur dadurch kollidieren die Mouse-Tweaks-Gesten nicht mit dem Vanilla-Verteilen.
     */
    protected enum DragMode {
        /** Kein Zug aktiv. */
        NONE,
        /** Shift+Links: jeder berührte Slot wandert sofort ins andere Inventar. */
        QUICK_MOVE,
        /** Links mit belegtem Cursor: Vanilla — Slots sammeln, beim Loslassen gleichmäßig teilen. */
        DISTRIBUTE,
        /** Links mit leerem Cursor: passende Items aus den berührten Slots einsammeln. */
        COLLECT,
        /** Rechts mit belegtem Cursor: je berührtem Slot sofort ein Item. */
        PLACE_ONE
    }

    private DragMode dragMode = DragMode.NONE;
    private int dragButton = -1;
    private Slot dragStartSlot;
    private Slot lastDragSlot;
    private final List<Slot> dragSlots = new ArrayList<>();

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
            if (s.contains(mx, my, SLOT, HIT_PAD)) return s;
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
     * <p>Aus dem Container heraus läuft die Liste RÜCKWÄRTS — das ist MCs
     * {@code moveItemStackTo(..., reverseDirection = true)} über die Spieler-Slots, die dort in
     * der Reihenfolge [Hauptinventar, Hotbar] liegen. Rückwärts heißt damit: Hotbar von RECHTS
     * nach links zuerst (Slot 8, dann 7, …), erst danach das Hauptinventar von unten rechts nach
     * oben links. Die Grundreihenfolge muss deshalb Hauptinventar VOR Hotbar sein.
     */
    protected List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.CONTAINER) {
            List<Slot> targets = new ArrayList<>(this.slotsOf(SlotGroup.INVENTORY));
            targets.addAll(this.slotsOf(SlotGroup.HOTBAR));
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
        ItemStack moving = from.take(amount);
        int wanted = moving.getCount();

        this.moveInto(moving, this.quickMoveTargets(from.group));

        /* Nicht untergebrachten Rest zurücklegen — split hat ihn schon abgezogen. */
        if (!moving.isEmpty()) {
            ItemStack existing = from.get();
            if (existing.isEmpty()) from.set(moving);
            else {
                existing.setCount(existing.getCount() + moving.getCount());
                from.setChanged();
            }
        }
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
            if (!target.canPlace(stack)) continue;
            if (!existing.canStackWith(stack)) continue;
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) continue;
            existing.setCount(existing.getCount() + stack.split(space).getCount());
            target.setChanged();
        }
        for (Slot target : targets) {
            if (stack.isEmpty()) return;
            if (!target.get().isEmpty()) continue;
            if (!target.canPlace(stack)) continue;
            target.set(stack.split(stack.getMaxStackSize()));
        }
    }

    /* --- Klicks --- */

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot != null) {
            boolean shift = SkyEngine.get().getInput().isShiftDown();
            if (this.isDoubleClick(slot, button, shift)) {
                this.collectMatching();
                this.lastClickSlot = null;   // Dreifachklick darf nicht erneut auslösen
                return true;
            }
            this.beginDrag(slot, button, shift);
            return true;
        }
        /* Klick neben das Fenster wirft den getragenen Stapel aus: links alles, rechts einzeln. */
        if (!this.carried.isEmpty() && !this.isInsideWindow(mouseX, mouseY)) {
            if (isCommandOnly(this.carried)) return true;
            this.throwFromCarried(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? 1 : this.carried.getCount());
            return true;
        }
        return false;
    }

    /* --- Ziehen (Vanilla-Verteilen + Mouse Tweaks) --- */

    /**
     * Legt beim Drücken den Zug-Modus fest und führt die sofort wirkenden Gesten gleich aus.
     * Einzig {@link DragMode#DISTRIBUTE} kann nicht sofort wirken — die Anteile hängen davon ab,
     * wie viele Slots am Ende berührt wurden.
     */
    private void beginDrag(Slot slot, int button, boolean shift) {
        this.endDrag();
        this.dragButton = button;
        this.dragStartSlot = slot;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && shift) {
            this.dragMode = DragMode.QUICK_MOVE;
            this.dragSlots.add(slot);
            this.onSlotClick(slot, button, true);
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !this.carried.isEmpty()) {
            this.dragMode = DragMode.DISTRIBUTE;
            if (this.canDistributeTo(slot)) this.dragSlots.add(slot);
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            /* Leerer Cursor: der Klick nimmt auf, das Ziehen sammelt weiter ein (Mouse Tweaks). */
            this.onSlotClick(slot, button, false);
            this.dragMode = DragMode.COLLECT;
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !this.carried.isEmpty()) {
            this.dragMode = DragMode.PLACE_ONE;
            this.lastDragSlot = slot;
            this.onSlotClick(slot, button, false);   // legt genau ein Item ab
        } else {
            this.onSlotClick(slot, button, shift);
        }
    }

    @Override
    public void mouseDragged(GuiManager gui, double mouseX, double mouseY, int button) {
        super.mouseDragged(gui, mouseX, mouseY, button);
        if (this.dragMode == DragMode.NONE || button != this.dragButton) return;
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot == null) {
            this.lastDragSlot = null;   // Slot verlassen: erneutes Betreten darf wieder ablegen
            return;
        }
        switch (this.dragMode) {
            case QUICK_MOVE -> {
                /* Nur die Seite des ersten Slots abräumen — sonst schöbe ein Zug quer über das
                   GUI die eben verschobenen Items sofort wieder zurück. */
                if (this.dragSlots.contains(slot) || !this.sameQuickMoveSide(this.dragStartSlot, slot)) return;
                this.dragSlots.add(slot);
                ItemStack stack = slot.get();
                if (!stack.isEmpty()) this.quickMove(slot, stack.getCount());
            }
            case DISTRIBUTE -> {
                if (!this.dragSlots.contains(slot) && this.canDistributeTo(slot)) this.dragSlots.add(slot);
            }
            case COLLECT -> this.collectFrom(slot);
            case PLACE_ONE -> {
                if (slot == this.lastDragSlot) return;
                this.lastDragSlot = slot;
                this.placeOne(slot);
            }
            default -> { }
        }
    }

    @Override
    public void mouseReleased(GuiManager gui, double mouseX, double mouseY, int button) {
        super.mouseReleased(gui, mouseX, mouseY, button);
        if (this.dragMode == DragMode.NONE || button != this.dragButton) return;
        if (this.dragMode == DragMode.DISTRIBUTE) {
            if (this.dragSlots.size() > 1) {
                this.applyDistribute();
            } else {
                /* Kein echter Zug: wie ein normaler Linksklick behandeln (so macht es MC auch) —
                   sonst würde ein simpler Klick auf einen fremden Stapel nicht mehr tauschen. */
                this.onSlotClick(this.dragStartSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
            }
        }
        this.endDrag();
    }

    private void endDrag() {
        this.dragMode = DragMode.NONE;
        this.dragButton = -1;
        this.dragStartSlot = null;
        this.lastDragSlot = null;
        this.dragSlots.clear();
    }

    /**
     * Gehören beide Slots zur selben Zug-Seite? Im Container-GUI zählen Hauptinventar und Hotbar
     * als eine Seite — sie haben dasselbe Quickmove-Ziel (den Container).
     */
    private boolean sameQuickMoveSide(Slot a, Slot b) {
        if (a.group == b.group) return true;
        return !this.slotsOf(SlotGroup.CONTAINER).isEmpty()
                && a.group != SlotGroup.CONTAINER && b.group != SlotGroup.CONTAINER;
    }

    /** Legt genau ein Item ab; anders als der Rechtsklick tauscht es NIE (Zug über fremde Stapel). */
    private void placeOne(Slot slot) {
        if (this.carried.isEmpty() || !this.canDistributeTo(slot)) return;
        ItemStack existing = slot.get();
        if (existing.isEmpty()) {
            slot.set(this.carried.split(1));
        } else {
            existing.setCount(existing.getCount() + this.carried.split(1).getCount());
            slot.setChanged();
        }
        if (this.carried.isEmpty()) this.carried = ItemStack.EMPTY;
    }

    /** Kann der getragene Stapel in diesem Slot überhaupt landen? (MC {@code canItemQuickReplace}) */
    private boolean canDistributeTo(Slot slot) {
        if (!slot.canPlace(this.carried)) return false;
        ItemStack existing = slot.get();
        return existing.isEmpty()
                || (existing.canStackWith(this.carried) && existing.getCount() < existing.getMaxStackSize());
    }

    /** Zieht passende Items aus {@code slot} in die Hand (Mouse-Tweaks-LMB-Zug). */
    private void collectFrom(Slot slot) {
        if (this.carried.isEmpty()) return;
        int space = this.carried.getMaxStackSize() - this.carried.getCount();
        if (space <= 0) return;
        ItemStack stack = slot.get();
        if (!stack.canStackWith(this.carried)) return;
        ItemStack taken = slot.take(space);
        this.carried.setCount(this.carried.getCount() + taken.getCount());
    }

    /**
     * Anteil, den {@code slot} beim Verteilen bekommt. {@code carriedCount} wird übergeben, weil
     * die Anwendung den Stand VOR der ersten Mutation braucht — sonst schrumpft der Anteil
     * mitten in der Schleife und Vorschau und Ergebnis liefen auseinander.
     */
    private int dragShare(Slot slot, int carriedCount) {
        ItemStack existing = slot.get();
        int space = existing.isEmpty()
                ? this.carried.getMaxStackSize()
                : existing.getMaxStackSize() - existing.getCount();
        return Math.min(carriedCount / this.dragSlots.size(), space);
    }

    /** Vorschau-Menge für {@code slot} während eines laufenden Verteil-Zugs (0 = nicht betroffen). */
    protected int dragPreview(Slot slot) {
        if (this.dragMode != DragMode.DISTRIBUTE || !this.dragSlots.contains(slot)) return 0;
        return this.dragShare(slot, this.carried.getCount());
    }

    private void applyDistribute() {
        int carriedCount = this.carried.getCount();
        for (Slot slot : this.dragSlots) {
            int give = this.dragShare(slot, carriedCount);
            if (give <= 0) continue;
            ItemStack existing = slot.get();
            if (existing.isEmpty()) {
                slot.set(this.carried.split(give));
            } else {
                existing.setCount(existing.getCount() + this.carried.split(give).getCount());
                slot.setChanged();
            }
        }
        if (this.carried.isEmpty()) this.carried = ItemStack.EMPTY;
    }

    /**
     * Mausrad über einem Slot (Mouse-Tweaks-Wheel-Tweak): hoch schiebt ein Item ins andere
     * Inventar, runter holt eins zurück. Über einem leeren Slot tut „runter" nichts — ohne Item
     * fehlt der Typ als Referenz.
     */
    @Override
    public boolean mouseScrolled(GuiManager gui, double mouseX, double mouseY, double amount) {
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot == null || !this.carried.isEmpty()) return false;
        if (amount > 0) {
            this.quickMove(slot, 1);
        } else {
            this.pullOne(slot);
        }
        return true;
    }

    /** Holt ein passendes Item aus dem anderen Bereich in {@code slot} zurück. */
    private void pullOne(Slot slot) {
        ItemStack target = slot.get();
        if (target.isEmpty() || target.getCount() >= target.getMaxStackSize() || !slot.canPlace(target)) return;
        for (Slot source : this.quickMoveTargets(slot.group)) {
            ItemStack stack = source.get();
            if (!stack.canStackWith(target)) continue;
            target.setCount(target.getCount() + source.take(1).getCount());
            slot.setChanged();
            return;
        }
    }

    /**
     * Tasten im Container-GUI: Zahlentasten 1-9 tauschen den Slot unter der Maus mit dem
     * zugehörigen Hotbar-Slot (MC {@code ClickType.SWAP}), die Drop-Taste (Default Q) wirft mit
     * belegtem Cursor von IHM, sonst vom Slot unter der Maus — mit STRG jeweils den ganzen
     * Stapel. Die Mausposition kommt aus dem {@link GuiManager}, {@code keyPressed} bekommt sie
     * nicht übergeben.
     */
    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        if (super.keyPressed(gui, key)) return true;   // fokussiertes Widget + Default-ESC zuerst
        GameSettings settings = GameSettings.get();

        for (int i = 0; i < 9; i++) {
            if (key != settings.key(KeyBindings.hotbar(i + 1))) continue;
            Slot hovered = this.slotAt(gui.mouseX(), gui.mouseY());
            if (hovered != null) this.swapWithHotbar(hovered, i);
            return true;
        }
        if (key != settings.key(KeyBindings.DROP)) return false;

        boolean fullStack = SkyEngine.get().getInput().isCtrlDown();
        if (!this.carried.isEmpty()) {
            if (isCommandOnly(this.carried)) {
                SkyEngine.get().getGame().clearWorldEditSelection();
                return true;
            }
            this.throwFromCarried(fullStack ? this.carried.getCount() : 1);
            return true;
        }
        Slot slot = this.slotAt(gui.mouseX(), gui.mouseY());
        if (slot != null && !slot.get().isEmpty()) {
            if (isCommandOnly(slot.get())) {
                SkyEngine.get().getGame().clearWorldEditSelection();
                return true;
            }
            throwOut(slot.storage.extract(slot.index, fullStack ? slot.get().getCount() : 1));
            slot.setChanged();
        }
        return true;
    }

    /**
     * Zweiter Linksklick auf denselben Slot im Zeitfenster, mit belegtem Cursor. Der erste Klick
     * hat den Stapel aufgenommen — deshalb muss diese Prüfung VOR {@link #onSlotClick} laufen,
     * sonst legt der zweite Klick ihn einfach wieder ab.
     */
    private boolean isDoubleClick(Slot slot, int button, boolean shift) {
        long now = System.currentTimeMillis();
        boolean doubleClick = button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !shift
                && slot == this.lastClickSlot && now - this.lastClickTime <= DOUBLE_CLICK_MS
                && !this.carried.isEmpty();
        this.lastClickSlot = slot;
        this.lastClickTime = now;
        return doubleClick;
    }

    /** Tauscht {@code slot} mit dem Hotbar-Slot {@code hotbarIndex} (MC {@code ClickType.SWAP}). */
    private void swapWithHotbar(Slot slot, int hotbarIndex) {
        for (Slot hotbar : this.slots) {
            if (hotbar.group != SlotGroup.HOTBAR || hotbar.index != hotbarIndex) continue;
            if (hotbar == slot) return;
            ItemStack previous = hotbar.get();
            if (!slot.canPlace(previous) || !hotbar.canPlace(slot.get())) return;
            hotbar.set(slot.get());
            slot.set(previous);
            return;
        }
    }

    /**
     * Doppelklick: alle passenden Items aus dem GUI in die Hand ziehen (MC
     * {@code ClickType.PICKUP_ALL}) — erst aus Teilstapeln, dann aus vollen.
     */
    private void collectMatching() {
        if (this.carried.isEmpty()) return;
        this.collectPass(false);
        this.collectPass(true);
    }

    private void collectPass(boolean fromFullStacks) {
        for (Slot s : this.slots) {
            int space = this.carried.getMaxStackSize() - this.carried.getCount();
            if (space <= 0) return;   // greift auch für Werkzeuge (Stapelgröße 1)
            ItemStack stack = s.get();
            if (!stack.canStackWith(this.carried)) continue;
            if ((stack.getCount() >= stack.getMaxStackSize()) != fromFullStacks) continue;
            this.carried.setCount(this.carried.getCount() + s.take(space).getCount());
        }
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

    private static boolean isCommandOnly(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() != null && Items.isCommandOnly(stack.getItem().getId());
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
            this.carried = slot.take(slotStack.getCount());
        } else if (slotStack.isEmpty()) {
            if (!slot.canPlace(this.carried)) return;
            slot.set(this.carried);
            this.carried = ItemStack.EMPTY;
        } else if (slotStack.canStackWith(this.carried)) {
            if (!slot.canPlace(this.carried)) {
                int space = this.carried.getMaxStackSize() - this.carried.getCount();
                ItemStack taken = slot.take(space);
                this.carried.setCount(this.carried.getCount() + taken.getCount());
                return;
            }
            int space = slotStack.getMaxStackSize() - slotStack.getCount();
            int move = Math.min(space, this.carried.getCount());
            slotStack.setCount(slotStack.getCount() + move);
            slot.setChanged();
            this.carried.setCount(this.carried.getCount() - move);
            if (this.carried.getCount() <= 0) this.carried = ItemStack.EMPTY;
        } else {
            if (!slot.canPlace(this.carried)) return;
            slot.set(this.carried);
            this.carried = slotStack;
        }
    }

    /** Rechtsklick: mit leerer Hand die Hälfte aufnehmen (aufgerundet), sonst 1 Item ablegen. */
    private void rightClick(Slot slot) {
        ItemStack slotStack = slot.get();
        if (this.carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            int amount = slot.canPlace(slotStack) ? (slotStack.getCount() + 1) / 2 : slotStack.getCount();
            this.carried = slot.take(amount);
            return;
        }
        if (!slot.canPlace(this.carried)) {
            if (!slotStack.canStackWith(this.carried)) return;
            int space = this.carried.getMaxStackSize() - this.carried.getCount();
            ItemStack taken = slot.take(space);
            this.carried.setCount(this.carried.getCount() + taken.getCount());
            return;
        }
        if (slotStack.isEmpty()) {
            slot.set(this.carried.split(1));
        } else if (slotStack.canStackWith(this.carried)
                && slotStack.getCount() < slotStack.getMaxStackSize()) {
            slotStack.setCount(slotStack.getCount() + this.carried.split(1).getCount());
            slot.setChanged();
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
        if (DebugFlags.guiSlotBounds) this.drawSlotBounds(gui);
        Slot hover = this.slotAt(mouseX, mouseY);
        if (hover != null) {
            /* Das Highlight bleibt die 16×16-Innenfläche (wie MC) — nur die TREFFERfläche
               ist um HIT_PAD größer. */
            gui.sprites().drawRect(hover.x, hover.y, SLOT, SLOT, 1f, 1f, 1f, 0.35f);
        }
    }

    /**
     * Debug: malt jede TREFFERfläche in einer eigenen Farbe. Bleibt irgendwo ein grauer Spalt
     * sichtbar, ist dort eine tote Zone, in der ein Ablegen ins Leere ginge.
     */
    private void drawSlotBounds(GuiManager gui) {
        int size = SLOT + 2 * HIT_PAD;
        for (int i = 0; i < this.slots.size(); i++) {
            Slot s = this.slots.get(i);
            /* Goldener Schnitt als Farbton-Schritt: benachbarte Slots landen dadurch weit
               auseinander im Farbkreis und sind sicher unterscheidbar. */
            float hue = (i * 0.618034f) % 1f;
            float h6 = hue * 6f;
            int sector = (int) h6;
            float f = h6 - sector;
            float r, g, b;
            switch (sector) {
                case 0 -> { r = 1; g = f; b = 0; }
                case 1 -> { r = 1 - f; g = 1; b = 0; }
                case 2 -> { r = 0; g = 1; b = f; }
                case 3 -> { r = 0; g = 1 - f; b = 1; }
                case 4 -> { r = f; g = 0; b = 1; }
                default -> { r = 1; g = 0; b = 1 - f; }
            }
            gui.sprites().drawRect(s.x - HIT_PAD, s.y - HIT_PAD, size, size, r, g, b, 0.45f);
        }
    }

    /**
     * Alle Slot-Icons + den getragenen Stapel am Cursor zeichnen (Icon-Pass + Zahlen-Pass).
     * Während eines Verteil-Zugs zeigen die betroffenen Slots schon ihren Anteil und der Cursor
     * nur noch den Rest — wie in Minecraft.
     */
    protected void drawSlotIcons(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        ItemStack carriedShown = this.carriedAfterDrag();

        gui.icons().begin(vW, vH);
        for (Slot s : this.slots) {
            ItemStack st = this.stackShownIn(s);
            if (!st.isEmpty()) gui.icons().drawIcon(st, s.x + SLOT / 2f, s.y + SLOT / 2f, SLOT, vH);
        }
        gui.icons().end();

        /* Stack-Zahlen NACH dem Icon-Pass (Depth aus -> Text liegt über den 3D-Icons),
           unten rechts im Slot wie in Minecraft. */
        gui.font().begin(vW, vH);
        for (Slot s : this.slots) {
            StackText.draw(gui, this.stackShownIn(s), s.x, s.y, SLOT);
        }
        gui.font().end();

        /* Der getragene Stapel GANZ zum Schluss in eigenen Pässen (Muster wie Tooltip): er hängt
           am Cursor über bis zu vier Nachbarslots, und deren Stack-Zahlen liefen ohne Tiefentest
           sonst quer über sein Icon. */
        if (carriedShown.isEmpty()) return;
        gui.icons().begin(vW, vH);
        gui.icons().drawIcon(carriedShown, (float) mouseX, (float) mouseY, SLOT, vH);
        gui.icons().end();

        gui.font().begin(vW, vH);
        StackText.draw(gui, carriedShown,
                (float) mouseX - SLOT / 2f, (float) mouseY - SLOT / 2f, SLOT);
        gui.font().end();
    }

    /** Was in diesem Slot zu sehen ist — inklusive der Vorschau eines laufenden Verteil-Zugs. */
    private ItemStack stackShownIn(Slot slot) {
        int extra = this.dragPreview(slot);
        ItemStack actual = slot.get();
        if (extra <= 0) return actual;
        ItemStack preview = (actual.isEmpty() ? this.carried : actual).copy();
        preview.setCount((actual.isEmpty() ? 0 : actual.getCount()) + extra);
        return preview;
    }

    /** Der getragene Stapel abzüglich dessen, was ein laufender Verteil-Zug schon vergibt. */
    private ItemStack carriedAfterDrag() {
        if (this.dragMode != DragMode.DISTRIBUTE || this.dragSlots.isEmpty()) return this.carried;
        int carriedCount = this.carried.getCount();
        int given = 0;
        for (Slot s : this.dragSlots) given += this.dragShare(s, carriedCount);
        if (given >= carriedCount) return ItemStack.EMPTY;
        ItemStack rest = this.carried.copy();
        rest.setCount(carriedCount - given);
        return rest;
    }

    /** Zeilen des Tooltips eines Stacks inklusive statischer und laufzeitberechneter Angaben. */
    protected List<RichText> tooltipLines(ItemStack stack) {
        List<RichText> lines = new ArrayList<>();
        lines.add(stack.getDisplayNameText());
        GameContainer game = SkyEngine.get().getGame();
        stack.getItem().appendTooltip(stack,
                new TooltipContext(game.getDimension(), game.getPlayer()), lines);
        lines.add(RichText.plain(stack.getItem().getId().toString(), Colors.DARK_GRAY));
        return lines;
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
        if (game.getDimension() != null && !isCommandOnly(this.carried)) {
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
