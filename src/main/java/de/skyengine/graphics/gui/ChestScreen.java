package de.skyengine.graphics.gui;

import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.texture.Texture;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI der Truhe mit der MC-Textur {@code container/generic_54.png}: 27 Truhen-Slots oben, darunter das
 * Spielerinventar (27 Haupt + 9 Hotbar) im MC-Standardlayout (176×167). Items werden per Mausklick
 * verschoben (getragener Stapel am Cursor). Öffnen lässt den Deckel aufgehen, Schließen legt einen
 * getragenen Rest zurück und schließt den Deckel.
 */
public final class ChestScreen extends Screen {

    private static final int W = 176, H = 167;
    private static final int COLS = 9, SLOT = 16, STEP = 18;
    private static final float TEX = 256f;

    private final ChestBlockEntity chest;
    private final ItemStorage chestInv;
    private final ItemStorage playerInv;

    private ItemStack carried = ItemStack.EMPTY;
    private final List<Slot> slots = new ArrayList<>();
    private float guiX, guiY;

    public ChestScreen(ChestBlockEntity chest, ItemStorage playerInv) {
        this.chest = chest;
        this.chestInv = chest.getInventory();
        this.playerInv = playerInv;
        chest.setOpen(true);
    }

    private void layout(float vW, float vH) {
        this.guiX = (vW - W) / 2f;
        this.guiY = (vH - H) / 2f;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);

        this.slots.clear();
        /* Truhe (3 Reihen). */
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < COLS; c++)
                this.slots.add(new Slot(this.chestInv, r * COLS + c, gx + 8 + c * STEP, gy + 18 + r * STEP));
        /* Spieler-Hauptinventar (Indizes 9..35). */
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < COLS; c++)
                this.slots.add(new Slot(this.playerInv, COLS + r * COLS + c, gx + 8 + c * STEP, gy + 85 + r * STEP));
        /* Hotbar (Indizes 0..8). */
        for (int c = 0; c < COLS; c++)
            this.slots.add(new Slot(this.playerInv, c, gx + 8 + c * STEP, gy + 143));
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        this.layout(vW, vH);

        SpriteRenderer sr = gui.sprites();
        Texture bg = gui.textures().chestBackground;

        sr.begin(vW, vH);
        sr.drawRect(0, 0, vW, vH, 0f, 0f, 0f, 0.4f); // Welt abdunkeln
        /* Hintergrund aus generic_54: oberer Truhen-Teil + unterer Spielerinventar-Teil. */
        sr.drawSprite(bg, this.guiX, this.guiY, W, 71, 0, 0, W / TEX, 71 / TEX);
        sr.drawSprite(bg, this.guiX, this.guiY + 71, W, 96, 0, 126 / TEX, W / TEX, 222 / TEX);

        Slot hover = this.slotAt(mouseX, mouseY);
        if (hover != null) sr.drawRect(hover.x, hover.y, SLOT, SLOT, 1f, 1f, 1f, 0.35f);
        sr.end();

        gui.icons().begin(vW, vH);
        for (Slot s : this.slots) {
            ItemStack st = s.get();
            if (!st.isEmpty()) gui.icons().drawIcon(st, s.x + SLOT / 2f, s.y + SLOT / 2f, SLOT, vH);
        }
        if (!this.carried.isEmpty()) gui.icons().drawIcon(this.carried, (float) mouseX, (float) mouseY, SLOT, vH);
        gui.icons().end();
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot == null) return;

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

    @Override
    public void onClose() {
        this.chest.setOpen(false);
        if (!this.carried.isEmpty()) {
            ItemStack rest = this.playerInv.insert(this.carried);
            if (!rest.isEmpty()) this.chestInv.insert(rest);
            this.carried = ItemStack.EMPTY;
        }
    }

    private Slot slotAt(double mx, double my) {
        for (Slot s : this.slots) if (s.contains(mx, my, SLOT)) return s;
        return null;
    }
}
