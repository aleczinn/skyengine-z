package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.HopperBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.texture.Texture;

/**
 * GUI des Trichters mit der MC-Textur {@code container/hopper.png} (Fenster 176×133):
 * 5 Trichter-Slots in der Mitte, darunter das Spielerinventar. Slot-/Carried-Logik kommt
 * aus {@link GuiContainer}. Inhaltsänderungen informieren messende Komparatoren unmittelbar.
 */
public final class GuiHopper extends GuiContainer {

    private static final int W = 176, H = 133;
    private static final float TEX = 256f;

    private final ItemStorage hopperInv;
    private final ItemStorage playerInv;
    private float guiX, guiY;

    public GuiHopper(HopperBlockEntity hopper, ItemStorage playerInv) {
        super(playerInv, hopper.getInventory());
        this.hopperInv = hopper.getInventory();
        this.playerInv = playerInv;
    }

    @Override
    protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.guiX && mx < this.guiX + W && my >= this.guiY && my < this.guiY + H;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.guiX = (vW - W) / 2f;
        this.guiY = (vH - H) / 2f;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);

        this.slots.clear();
        /* Die 5 Trichter-Slots (aus dem MC-Sheet gescannt: ab x=44, y=20). */
        for (int c = 0; c < HopperBlockEntity.SLOTS; c++) {
            this.slots.add(new Slot(this.hopperInv, c, gx + 44 + c * STEP, gy + 20,
                    SlotGroup.CONTAINER));
        }
        /* Spieler-Hauptinventar (Indizes 9..35) ab y=51, Hotbar (0..8) bei y=109. */
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < COLS; c++)
                this.slots.add(new Slot(this.playerInv, COLS + r * COLS + c, gx + 8 + c * STEP,
                        gy + 51 + r * STEP, SlotGroup.INVENTORY));
        for (int c = 0; c < COLS; c++)
            this.slots.add(new Slot(this.playerInv, c, gx + 8 + c * STEP, gy + 109,
                    SlotGroup.HOTBAR));
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        SpriteRenderer sr = gui.sprites();
        Texture bg = gui.textures().hopperBackground;

        sr.begin(vW, vH);
        this.renderBackground(gui);
        sr.drawSprite(bg, this.guiX, this.guiY, W, H, 0, 0, W / TEX, H / TEX);
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();

        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }

}
