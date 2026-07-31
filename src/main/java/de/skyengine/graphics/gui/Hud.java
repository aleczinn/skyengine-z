package de.skyengine.graphics.gui;

import de.skyengine.game.Gamemode;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.color.Color4;

/**
 * In-Game-HUD (gezeichnet, wenn kein GuiScreen offen ist): Fadenkreuz + Hotbar mit Auswahlrahmen,
 * 3D-Item-Icons und Stack-Zahlen. MC-Texturen: {@code sprites/hud/hotbar.png} (182×22),
 * {@code hotbar_selection.png} (24×23), {@code crosshair_circle.png} (15×15).
 * Beim Slot-Wechsel blendet der Itemname über der Hotbar ein/aus ({@code itemNameAlpha},
 * berechnet im GameContainer). Im SURVIVAL kommen Herzen (links) + Hungerbalken (rechts)
 * über der Hotbar dazu (9×9-Sprites, Raster 8 wie MC).
 */
public final class Hud {

    private static final float HOTBAR_W = 182, HOTBAR_H = 22;
    private static final float SLOT_STEP = 20;     // Slotabstand in der Hotbar-Textur
    private static final float SEL_W = 24, SEL_H = 23;
    private static final float CROSS = 8;
    private static final float ICON = 16;
    private static final float NAME_TEXT = GuiText.NORMAL;
    /* Vitals-Reihe: 9×9-Icons im 8er-Raster, direkt über der Hotbar (MC-Layout). */
    private static final float VITAL = 9, VITAL_STEP = 8;

    public void render(GuiManager gui, SimpleItemStorage inv, int selectedSlot, boolean drawCrosshair,
                       boolean drawHotbar, float itemNameAlpha, EntityPlayer player) {
        SpriteRenderer sr = gui.sprites();
        GuiTextures tex = gui.textures();
        float vW = gui.vWidth(), vH = gui.vHeight();

        float hx = (vW - HOTBAR_W) / 2f;
        float hy = vH - HOTBAR_H - 2f;

        sr.begin(vW, vH);
        if (drawCrosshair)  {
            sr.drawSprite(tex.crosshair, (vW - CROSS) / 2f, (vH - CROSS) / 2f, CROSS, CROSS);
        }

        boolean vitals = player != null && player.getGamemode() == Gamemode.SURVIVAL;
        if (drawHotbar) {
            sr.drawSprite(tex.hotbar, hx, hy, HOTBAR_W, HOTBAR_H);
            sr.drawSprite(tex.hotbarSelection, hx - 1 + selectedSlot * SLOT_STEP, hy - 1, SEL_W, SEL_H);
            if (vitals) {
                this.drawVitals(sr, tex, player, hx, hy);
            }
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

            /* Im Survival sitzt die Vitals-Reihe über der Hotbar -> Name eine Etage höher. */
            float nameBase = vitals ? hy - VITAL - 1 : hy;
            this.drawSelectedItemName(gui, inv.get(selectedSlot), nameBase, vW, vH, itemNameAlpha);
        }
    }

    /**
     * Herzen (linksbündig ab der Hotbar-Kante) + Hungerbalken (rechtsbündig, von rechts nach
     * links gefüllt) über der Hotbar. 1 HP = halbes Herz; Icon i ist voll ab {@code 2*(i+1)},
     * halb bei genau {@code 2*i+1}.
     */
    private void drawVitals(SpriteRenderer sr, GuiTextures tex, EntityPlayer player, float hx, float hy) {
        float rowY = hy - VITAL - 1;
        int hp = (int) Math.ceil(player.getHealth());
        for (int i = 0; i < 10; i++) {
            float x = hx + i * VITAL_STEP;
            sr.drawSprite(tex.heartContainer, x, rowY, VITAL, VITAL);
            if (hp >= 2 * (i + 1)) {
                sr.drawSprite(tex.heartFull, x, rowY, VITAL, VITAL);
            } else if (hp == 2 * i + 1) {
                sr.drawSprite(tex.heartHalf, x, rowY, VITAL, VITAL);
            }
        }
        int food = player.getFoodLevel();
        for (int i = 0; i < 10; i++) {
            float x = hx + HOTBAR_W - VITAL - i * VITAL_STEP;
            sr.drawSprite(tex.foodEmpty, x, rowY, VITAL, VITAL);
            if (food >= 2 * (i + 1)) {
                sr.drawSprite(tex.foodFull, x, rowY, VITAL, VITAL);
            } else if (food == 2 * i + 1) {
                sr.drawSprite(tex.foodHalf, x, rowY, VITAL, VITAL);
            }
        }
    }

    /** Name des selektierten Items zentriert über {@code base} (Hotbar- bzw. Vitals-Oberkante). */
    private void drawSelectedItemName(GuiManager gui, ItemStack selected, float base, float vW, float vH, float alpha) {
        if (alpha <= 0 || selected.isEmpty()) return;
        String name = selected.getDisplayName();
        float x = (vW - gui.font().getStringWidth(name, NAME_TEXT)) / 2f;
        float y = base - gui.font().lineHeight(NAME_TEXT) - 4;
        gui.font().begin(vW, vH);
        gui.font().drawStringWithShadow(name, x, y, NAME_TEXT, new Color4(1f, 1f, 1f, alpha));
        gui.font().end();
    }
}
