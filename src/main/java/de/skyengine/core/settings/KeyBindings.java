package de.skyengine.core.settings;

import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard-Tastenbelegungen (Aktion -> GLFW-Key). Wird in {@link GameSettings#keyBindings}
 * gehalten und persistiert. Volles Rebinding + Rebinding-UI folgen mit dem Optionsmenü;
 * angewandt werden die Bindings vorerst auf der {@code GameContainer}-Ebene.
 */
public final class KeyBindings {

    public static final String OPEN_INVENTORY = "open_inventory";
    public static final String DROP = "drop";
    public static final String FLY = "fly";
    public static final String NOCLIP = "noclip";
    public static final String AMBIENT_OCCLUSION = "ambient_occlusion";

    /** Slot i (1..9) -> "hotbar_i". */
    public static String hotbar(int slot) {
        return "hotbar_" + slot;
    }

    public static Map<String, Integer> defaults() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(OPEN_INVENTORY, GLFW.GLFW_KEY_E);
        m.put(DROP, GLFW.GLFW_KEY_Q);
        m.put(FLY, GLFW.GLFW_KEY_F);
        m.put(NOCLIP, GLFW.GLFW_KEY_N);
        m.put(AMBIENT_OCCLUSION, GLFW.GLFW_KEY_O);
        for (int i = 1; i <= 9; i++) {
            m.put(hotbar(i), GLFW.GLFW_KEY_1 + (i - 1));
        }
        return m;
    }

    private KeyBindings() {}
}
