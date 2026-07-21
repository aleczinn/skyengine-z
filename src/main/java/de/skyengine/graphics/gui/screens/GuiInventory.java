package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.texture.Texture;

/**
 * Spielerinventar (Taste E) mit der MC-Textur {@code container/inventory.png} (176×166):
 * 27 Haupt-Slots + 9 Hotbar-Slots im Standardlayout. Der Crafting-/Rüstungs-Bereich der
 * Textur bleibt vorerst funktionslos (Phase 2).
 */
public final class GuiInventory extends GuiContainer {

    private static final int W = 176, H = 166;
    private static final float TEX = 256f;

    private final ItemStorage playerInv;
    private float guiX, guiY;

    public GuiInventory(ItemStorage playerInv) {
        super(playerInv);
        this.playerInv = playerInv;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.guiX = (vW - W) / 2f;
        this.guiY = (vH - H) / 2f;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);

        this.slots.clear();
        /* Hauptinventar (Indizes 9..35). */
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < COLS; c++)
                this.slots.add(new Slot(this.playerInv, COLS + r * COLS + c, gx + 8 + c * STEP, gy + 84 + r * STEP));
        /* Hotbar (Indizes 0..8). */
        for (int c = 0; c < COLS; c++)
            this.slots.add(new Slot(this.playerInv, c, gx + 8 + c * STEP, gy + 142));
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        SpriteRenderer sr = gui.sprites();
        Texture bg = gui.textures().inventoryBackground;

        sr.begin(vW, vH);
        this.renderBackground(gui);
        sr.drawSprite(bg, this.guiX, this.guiY, W, H, 0, 0, W / TEX, H / TEX);
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();

        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }
}
