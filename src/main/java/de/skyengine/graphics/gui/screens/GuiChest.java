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
 */
public final class GuiChest extends GuiContainer {

    private static final int W = 176, H = 167;
    private static final float TEX = 256f;

    private final ChestBlockEntity chest;
    private final ItemStorage chestInv;
    private final ItemStorage playerInv;
    private float guiX, guiY;

    public GuiChest(ChestBlockEntity chest, ItemStorage playerInv) {
        super(playerInv, chest.getInventory());
        this.chest = chest;
        this.chestInv = chest.getInventory();
        this.playerInv = playerInv;
        chest.setOpen(true);
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
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
        SpriteRenderer sr = gui.sprites();
        Texture bg = gui.textures().chestBackground;

        sr.begin(vW, vH);
        this.renderBackground(gui);
        /* Hintergrund aus generic_54: oberer Truhen-Teil + unterer Spielerinventar-Teil. */
        sr.drawSprite(bg, this.guiX, this.guiY, W, 71, 0, 0, W / TEX, 71 / TEX);
        sr.drawSprite(bg, this.guiX, this.guiY + 71, W, 96, 0, 126 / TEX, W / TEX, 222 / TEX);
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();

        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        this.chest.setOpen(false);
        super.onClose(); // getragenen Stapel zurücklegen (Spieler, dann Truhe)
    }
}
