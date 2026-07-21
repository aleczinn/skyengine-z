package de.skyengine.graphics.gui;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.color.Color4;

/**
 * In-Game-HUD (gezeichnet, wenn kein GuiScreen offen ist): Fadenkreuz + Hotbar mit Auswahlrahmen,
 * 3D-Item-Icons und Stack-Zahlen. MC-Texturen: {@code sprites/hud/hotbar.png} (182×22),
 * {@code hotbar_selection.png} (24×23), {@code crosshair_circle.png} (15×15).
 * Beim Slot-Wechsel blendet der Itemname über der Hotbar ein/aus ({@code itemNameAlpha},
 * berechnet im GameContainer).
 */
public final class Hud {

    private static final float HOTBAR_W = 182, HOTBAR_H = 22;
    private static final float SLOT_STEP = 20;     // Slotabstand in der Hotbar-Textur
    private static final float SEL_W = 24, SEL_H = 23;
    private static final float CROSS = 8;
    private static final float ICON = 16;
    private static final float NAME_TEXT = 10;

    public void render(GuiManager gui, SimpleItemStorage inv, int selectedSlot, boolean drawCrosshair,
                       boolean drawHotbar, float itemNameAlpha) {
        SpriteRenderer sr = gui.sprites();
        GuiTextures tex = gui.textures();
        float vW = gui.vWidth(), vH = gui.vHeight();

        float hx = (vW - HOTBAR_W) / 2f;
        float hy = vH - HOTBAR_H - 2f;

        sr.begin(vW, vH);
        if (drawCrosshair)  {
            sr.drawSprite(tex.crosshair, (vW - CROSS) / 2f, (vH - CROSS) / 2f, CROSS, CROSS);
        }

        if (drawHotbar) {
            sr.drawSprite(tex.hotbar, hx, hy, HOTBAR_W, HOTBAR_H);
            sr.drawSprite(tex.hotbarSelection, hx - 1 + selectedSlot * SLOT_STEP, hy - 1, SEL_W, SEL_H);
        }
        sr.end();

        if (drawHotbar) {
            gui.icons().begin(vW, vH);
            for (int i = 0; i < 9; i++) {
                ItemStack st = inv.get(i);
                if (!st.isEmpty()) {
                    gui.icons().drawIcon(st, hx + 11 + i * SLOT_STEP, hy + 11, ICON, vH);
                }
            }
            gui.icons().end();

            /* Stack-Zahlen über den Icons (Font-Pass nach dem Icon-Pass). Icon-Zentrum ist
               (hx+11+i*STEP, hy+11) -> Slot-Ecke = Zentrum - ICON/2. */
            gui.font().begin(vW, vH);
            for (int i = 0; i < 9; i++) {
                StackText.draw(gui, inv.get(i), hx + 11 + i * SLOT_STEP - ICON / 2f, hy + 11 - ICON / 2f, ICON);
            }
            gui.font().end();

            this.drawSelectedItemName(gui, inv.get(selectedSlot), hy, vW, vH, itemNameAlpha);
        }
    }

    /** Name des selektierten Items zentriert über der Hotbar (Alpha = Einblend-/Ausblendwert). */
    private void drawSelectedItemName(GuiManager gui, ItemStack selected, float hy, float vW, float vH, float alpha) {
        if (alpha <= 0 || selected.isEmpty()) return;
        String name = selected.getDisplayName();
        float x = (vW - gui.font().getStringWidth(name, NAME_TEXT)) / 2f;
        float y = hy - gui.font().lineHeight(NAME_TEXT) - 4;
        gui.font().begin(vW, vH);
        gui.font().drawStringWithShadow(name, x, y, NAME_TEXT, new Color4(1f, 1f, 1f, alpha));
        gui.font().end();
    }
}
