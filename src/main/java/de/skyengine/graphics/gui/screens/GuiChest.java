package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.texture.Texture;

/**
 * GUI der Truhe mit der MC-Textur {@code container/generic_54.png}: 27 Truhen-Slots oben,
 * darunter das Spielerinventar (27 Haupt + 9 Hotbar) im MC-Standardlayout (176×167).
 * Slot-/Carried-Logik kommt aus {@link GuiContainer}; Öffnen/Schließen steuert
 * den Truhendeckel.
 *
 * <p>Dieselbe Klasse bedient die Doppeltruhe: mit zweiter Hälfte werden es 6 Reihen (176×222).
 * Ein kombiniertes Inventar braucht es dafür nicht — jeder {@link Slot} zeigt auf sein eigenes
 * {@link ItemStorage}, die oberen drei Reihen also auf die eine, die unteren auf die andere Hälfte.
 */
public final class GuiChest extends GuiContainer {

    private static final int W = 176;
    private static final float TEX = 256f;
    /** Fensterhöhe nach MC: 114 + Reihen × 18 (bei 3 Reihen die bisherigen 167). */
    private static final int H_SINGLE = 167, H_DOUBLE = 222;

    private final ChestBlockEntity chest;
    private final ChestBlockEntity partner;
    private final ItemStorage chestInv;
    private final ItemStorage partnerInv;
    private final ItemStorage playerInv;
    private final int rows;
    private final int height;
    private float guiX, guiY;

    /** Einzeltruhe. */
    public GuiChest(ChestBlockEntity chest, ItemStorage playerInv) {
        this(chest, null, playerInv);
    }

    /**
     * Doppeltruhe, wenn {@code partner} nicht null ist. Die Slots von {@code chest} bilden die
     * oberen Reihen — der Aufrufer übergibt dafür die Hälfte, die oben stehen soll.
     */
    public GuiChest(ChestBlockEntity chest, ChestBlockEntity partner, ItemStorage playerInv) {
        super(partner == null
                ? new ItemStorage[]{playerInv, chest.getInventory()}
                : new ItemStorage[]{playerInv, chest.getInventory(), partner.getInventory()});
        this.chest = chest;
        this.partner = partner;
        this.chestInv = chest.getInventory();
        this.partnerInv = partner == null ? null : partner.getInventory();
        this.playerInv = playerInv;
        this.rows = partner == null ? 3 : 6;
        this.height = partner == null ? H_SINGLE : H_DOUBLE;
        chest.setOpen(true);
        /* Zweite Hälfte stumm öffnen: ein Deckelgeräusch, nicht zwei gleichzeitig. */
        if (partner != null) partner.setOpen(true, false);
    }

    @Override
    protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.guiX && mx < this.guiX + W && my >= this.guiY && my < this.guiY + this.height;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.guiX = (vW - W) / 2f;
        this.guiY = (vH - this.height) / 2f;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);

        /* MC-Raster: Truhenreihen ab 18, Spielerinventar 13 px darunter, Hotbar nochmal 58 tiefer. */
        int playerY = 18 + this.rows * STEP + 13;

        this.slots.clear();
        for (int r = 0; r < this.rows; r++) {
            ItemStorage storage = r < 3 ? this.chestInv : this.partnerInv;
            int base = (r % 3) * COLS;
            for (int c = 0; c < COLS; c++) {
                this.slots.add(new Slot(storage, base + c, gx + 8 + c * STEP, gy + 18 + r * STEP));
            }
        }
        /* Spieler-Hauptinventar (Indizes 9..35). */
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < COLS; c++)
                this.slots.add(new Slot(this.playerInv, COLS + r * COLS + c, gx + 8 + c * STEP, gy + playerY + r * STEP));
        /* Hotbar (Indizes 0..8). */
        for (int c = 0; c < COLS; c++)
            this.slots.add(new Slot(this.playerInv, c, gx + 8 + c * STEP, gy + playerY + 58));
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        SpriteRenderer sr = gui.sprites();
        Texture bg = gui.textures().chestBackground;

        sr.begin(vW, vH);
        this.renderBackground(gui);
        if (this.rows == 6) {
            /* Doppeltruhe = das komplette 6-Reihen-Fenster des Sheets. */
            sr.drawSprite(bg, this.guiX, this.guiY, W, H_DOUBLE, 0, 0, W / TEX, H_DOUBLE / TEX);
        } else {
            /* Einzeltruhe: oberer Truhen-Teil + unterer Spielerinventar-Teil, Mitte übersprungen. */
            sr.drawSprite(bg, this.guiX, this.guiY, W, 71, 0, 0, W / TEX, 71 / TEX);
            sr.drawSprite(bg, this.guiX, this.guiY + 71, W, 96, 0, 126 / TEX, W / TEX, 222 / TEX);
        }
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();

        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        this.chest.setOpen(false);
        if (this.partner != null) this.partner.setOpen(false, false);
        super.onClose(); // getragenen Stapel zurücklegen (Spieler, dann Truhe)
    }
}
