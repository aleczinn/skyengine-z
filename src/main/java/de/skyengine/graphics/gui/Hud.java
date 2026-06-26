package de.skyengine.graphics.gui;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.item.ItemStack;

/**
 * In-Game-HUD (gezeichnet, wenn kein Screen offen ist): Fadenkreuz + Hotbar mit Auswahlrahmen und
 * 3D-Item-Icons. MC-Texturen: {@code sprites/hud/hotbar.png} (182×22), {@code hotbar_selection.png}
 * (24×23), {@code crosshair_circle.png} (15×15). Stack-Zahlen folgen mit dem Font-System.
 */
public final class Hud {

    private static final float HOTBAR_W = 182, HOTBAR_H = 22;
    private static final float SLOT_STEP = 20;     // Slotabstand in der Hotbar-Textur
    private static final float SEL_W = 24, SEL_H = 23;
    private static final float CROSS = 8;
    private static final float ICON = 16;

    public void render(GuiManager gui, SimpleItemStorage inv, int selectedSlot, boolean drawCrosshair) {
        SpriteRenderer sr = gui.sprites();
        GuiTextures tex = gui.textures();
        float vW = gui.vWidth(), vH = gui.vHeight();

        float hx = (vW - HOTBAR_W) / 2f;
        float hy = vH - HOTBAR_H - 2f;

        sr.begin(vW, vH);
        if (drawCrosshair)  {
            sr.drawSprite(tex.crosshair, (vW - CROSS) / 2f, (vH - CROSS) / 2f, CROSS, CROSS);
        }

        sr.drawSprite(tex.hotbar, hx, hy, HOTBAR_W, HOTBAR_H);
        sr.drawSprite(tex.hotbarSelection, hx - 1 + selectedSlot * SLOT_STEP, hy - 1, SEL_W, SEL_H);
        sr.end();

        gui.icons().begin(vW, vH);
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.get(i);
            if (!st.isEmpty()) {
                gui.icons().drawIcon(st, hx + 11 + i * SLOT_STEP, hy + 11, ICON, vH);
            }
        }
        gui.icons().end();
    }
}
