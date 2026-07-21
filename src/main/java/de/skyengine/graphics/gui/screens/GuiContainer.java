package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.StackText;
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

    /* Tooltip-Optik: MC-Original-Farben (Hintergrund 0xF0100010, Rahmen-Gradient
       0x505000FF -> 0x5028007F) und -Geometrie (Padding 3, 1-px-Ecken-Notch, Rahmen innen). */
    private static final float TOOLTIP_TEXT = 8;
    private static final float TOOLTIP_PAD = 3;
    /** Extra-Abstand nach der Titelzeile (MC-Gap). */
    private static final float TOOLTIP_TITLE_GAP = 2;
    private static final Color4 TOOLTIP_GRAY = new Color4(0.67f, 0.67f, 0.67f, 1f);

    /** Eine Tooltip-Zeile mit eigener Farbe (Titel weiß, ID grau, später Stats). */
    protected record TooltipLine(String text, Color4 color) {}

    /** Zeilen des Tooltips eines Stacks — Andockpunkt für spätere Stats (Haltbarkeit etc.). */
    protected List<TooltipLine> tooltipLines(ItemStack stack) {
        return List.of(
                new TooltipLine(stack.getDisplayName(), Colors.WHITE),
                new TooltipLine(stack.getItem().getId().toString(), TOOLTIP_GRAY));
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
        List<TooltipLine> lines = this.tooltipLines(slot.get());
        if (lines.isEmpty()) return;

        float vW = gui.vWidth(), vH = gui.vHeight();
        float lineStep = gui.font().lineHeight(TOOLTIP_TEXT) + 1;
        float textW = 0;
        for (TooltipLine line : lines) {
            textW = Math.max(textW, gui.font().getStringWidth(line.text(), TOOLTIP_TEXT));
        }
        float w = textW + TOOLTIP_PAD * 2;
        float h = lines.size() * lineStep - 1 + TOOLTIP_PAD * 2
                + (lines.size() > 1 ? TOOLTIP_TITLE_GAP : 0);
        /* Rechts neben dem Cursor, an den Bildschirmrändern geklemmt (wie MC). */
        float x = Math.clamp((float) mouseX + 12, 2, Math.max(2, vW - w - 2));
        float y = Math.clamp((float) mouseY - 12, 2, Math.max(2, vH - h - 2));

        SpriteRenderer sr = gui.sprites();
        sr.begin(vW, vH);
        /* Hintergrund mit 1-px-Ecken-Notch: Mittelteil volle Höhe + schmale Randspalten. */
        sr.drawRect(x + 1, y, w - 2, h, 0.063f, 0f, 0.063f, 0.94f);
        sr.drawRect(x, y + 1, 1, h - 2, 0.063f, 0f, 0.063f, 0.94f);
        sr.drawRect(x + w - 1, y + 1, 1, h - 2, 0.063f, 0f, 0.063f, 0.94f);
        /* Rahmen 1 px INNEN, vertikaler Violett-Gradient (Seiten als zwei Hälften angenähert). */
        float half = (h - 4) / 2f;
        sr.drawRect(x + 1, y + 1, w - 2, 1, 0.31f, 0f, 1f, 0.31f);
        sr.drawRect(x + 1, y + h - 2, w - 2, 1, 0.157f, 0f, 0.5f, 0.31f);
        sr.drawRect(x + 1, y + 2, 1, half, 0.31f, 0f, 1f, 0.31f);
        sr.drawRect(x + 1, y + 2 + half, 1, half, 0.157f, 0f, 0.5f, 0.31f);
        sr.drawRect(x + w - 2, y + 2, 1, half, 0.31f, 0f, 1f, 0.31f);
        sr.drawRect(x + w - 2, y + 2 + half, 1, half, 0.157f, 0f, 0.5f, 0.31f);
        sr.end();

        gui.font().begin(vW, vH);
        float ty = y + TOOLTIP_PAD;
        for (int i = 0; i < lines.size(); i++) {
            TooltipLine line = lines.get(i);
            gui.font().drawStringWithShadow(line.text(), x + TOOLTIP_PAD, ty, TOOLTIP_TEXT, line.color());
            ty += lineStep + (i == 0 ? TOOLTIP_TITLE_GAP : 0);
        }
        gui.font().end();
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
