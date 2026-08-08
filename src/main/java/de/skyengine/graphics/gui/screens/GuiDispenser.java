package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.DispenserBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.texture.Texture;

/** Gemeinsames 3x3-Inventarfenster von Dispenser und Dropper. */
public final class GuiDispenser extends GuiContainer {

    private static final int W = 176, H = 166;
    private static final float TEX = 256F;
    private final ItemStorage containerInv;
    private final ItemStorage playerInv;
    private float guiX, guiY;

    public GuiDispenser(DispenserBlockEntity dispenser, ItemStorage playerInv) {
        super(playerInv, dispenser.getInventory());
        this.containerInv = dispenser.getInventory();
        this.playerInv = playerInv;
    }

    @Override
    protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.guiX && mx < this.guiX + W && my >= this.guiY && my < this.guiY + H;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.guiX = (vW - W) / 2F;
        this.guiY = (vH - H) / 2F;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);
        this.slots.clear();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                this.slots.add(new Slot(this.containerInv, row * 3 + col,
                        gx + 62 + col * STEP, gy + 17 + row * STEP, SlotGroup.CONTAINER));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLS; col++) {
                this.slots.add(new Slot(this.playerInv, COLS + row * COLS + col,
                        gx + 8 + col * STEP, gy + 84 + row * STEP, SlotGroup.INVENTORY));
            }
        }
        for (int col = 0; col < COLS; col++) {
            this.slots.add(new Slot(this.playerInv, col, gx + 8 + col * STEP, gy + 142,
                    SlotGroup.HOTBAR));
        }
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        SpriteRenderer sprites = gui.sprites();
        Texture background = gui.textures().dispenserBackground;
        sprites.begin(gui.vWidth(), gui.vHeight());
        this.renderBackground(gui);
        sprites.drawSprite(background, this.guiX, this.guiY, W, H, 0, 0, W / TEX, H / TEX);
        this.drawSlotHover(gui, mouseX, mouseY);
        sprites.end();
        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }
}
