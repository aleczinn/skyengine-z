package de.skyengine.core.settings;

import de.skyengine.core.i18n.I18n;
import de.skyengine.core.input.Input;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard-Tastenbelegungen (Aktion -> GLFW-Key). Wird in {@link GameSettings#keyBindings}
 * gehalten und persistiert; umbelegbar im Tastenbelegungs-Menü. Die Reihenfolge von
 * {@link #defaults()} ist zugleich die Anzeige-Reihenfolge im Menü.
 */
public final class KeyBindings {

    public static final String FORWARD = "forward";
    public static final String BACK = "back";
    public static final String LEFT = "left";
    public static final String RIGHT = "right";
    public static final String JUMP = "jump";
    public static final String SNEAK = "sneak";
    public static final String SPRINT = "sprint";
    public static final String OPEN_INVENTORY = "open_inventory";
    public static final String DROP = "drop";
    public static final String ATTACK = "attack";
    public static final String USE = "use";
    public static final String TOGGLE_HUD = "toggle_hud";
    public static final String TOGGLE_PERSPECTIVE = "toggle_perspective";
    public static final String SCREENSHOT = "screenshot";
    public static final String GAMEMODE = "gamemode";
    public static final String PICK_BLOCK = "pick_block";

    /** Slot i (1..9) -> "hotbar_i". */
    public static String hotbar(int slot) {
        return "hotbar_" + slot;
    }

    public static Map<String, Integer> defaults() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(FORWARD, GLFW.GLFW_KEY_W);
        m.put(BACK, GLFW.GLFW_KEY_S);
        m.put(LEFT, GLFW.GLFW_KEY_A);
        m.put(RIGHT, GLFW.GLFW_KEY_D);
        m.put(JUMP, GLFW.GLFW_KEY_SPACE);
        m.put(SNEAK, GLFW.GLFW_KEY_LEFT_SHIFT);
        m.put(SPRINT, GLFW.GLFW_KEY_LEFT_CONTROL);
        m.put(OPEN_INVENTORY, GLFW.GLFW_KEY_E);
        m.put(DROP, GLFW.GLFW_KEY_Q);
        m.put(ATTACK, Input.mouseBind(GLFW.GLFW_MOUSE_BUTTON_LEFT));
        m.put(USE, Input.mouseBind(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        m.put(TOGGLE_HUD, GLFW.GLFW_KEY_F1);
        m.put(TOGGLE_PERSPECTIVE, GLFW.GLFW_KEY_F5);
        m.put(SCREENSHOT, GLFW.GLFW_KEY_F2);
        m.put(GAMEMODE, GLFW.GLFW_KEY_G);
        m.put(PICK_BLOCK, Input.mouseBind(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        for (int i = 1; i <= 9; i++) {
            m.put(hotbar(i), GLFW.GLFW_KEY_1 + (i - 1));
        }
        return m;
    }

    /** Anzeige-Reihenfolge fürs Menü (= Reihenfolge der Defaults). */
    public static List<String> orderedActions() {
        return new ArrayList<>(defaults().keySet());
    }

    /** Übersetzter Anzeigename einer Aktion (Keys {@code key.<aktion>} bzw. {@code key.hotbar}). */
    public static String label(String action) {
        return action.startsWith("hotbar_")
                ? I18n.tr("key.hotbar", action.substring("hotbar_".length()))
                : I18n.tr("key." + action);
    }

    private KeyBindings() {}
}
