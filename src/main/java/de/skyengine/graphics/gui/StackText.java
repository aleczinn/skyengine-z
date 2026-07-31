package de.skyengine.graphics.gui;

import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.color.Colors;

/**
 * Stack-Zahl eines Item-Stapels unten rechts im Slot (wie Minecraft, ab Anzahl 2).
 * Wird von Container-GUIs und der HUD-Hotbar geteilt; Aufruf im Font-Pass NACH dem
 * Icon-Pass (Depth aus -> Text liegt über den 3D-Icons).
 */
public final class StackText {

    private static final float SIZE = GuiText.NORMAL;

    public static void draw(GuiManager gui, ItemStack stack, float slotX, float slotY, float slotSize) {
        if (stack.isEmpty() || stack.getCount() <= 1) return;
        String text = String.valueOf(stack.getCount());
        float x = slotX + slotSize - gui.font().getStringWidth(text, SIZE) + 1;
        float y = slotY + slotSize - SIZE + 2;
        gui.font().drawStringWithShadow(text, x, y, SIZE, Colors.WHITE);
    }

    private StackText() {}
}
