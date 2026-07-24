package de.skyengine.core.input;

import org.lwjgl.glfw.GLFW;

/**
 * Anzeigenamen für GLFW-Keycodes — bewusst OHNE {@code glfwGetKeyName}: das ist laut GLFW
 * main-thread-only, das Tastenbelegungs-Menü rendert aber auf dem Render-Thread.
 */
public final class KeyNames {

    public static String name(int glfwKey) {
        /* Maustasten (ab Input.MOUSE_OFFSET codiert): 0/1/2 = links/rechts/mitte, sonst Nummer. */
        if (Input.isMouseBind(glfwKey)) {
            int button = glfwKey - Input.MOUSE_OFFSET;
            return switch (button) {
                case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "Maus links";
                case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "Maus rechts";
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "Maus mitte";
                default -> "Maus " + (button + 1);
            };
        }
        /* Druckbare Standard-Keys: A-Z, 0-9 und gängige Satzzeichen decken die US-Codes ab. */
        if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) ('A' + (glfwKey - GLFW.GLFW_KEY_A)));
        }
        if (glfwKey >= GLFW.GLFW_KEY_0 && glfwKey <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) ('0' + (glfwKey - GLFW.GLFW_KEY_0)));
        }
        if (glfwKey >= GLFW.GLFW_KEY_F1 && glfwKey <= GLFW.GLFW_KEY_F25) {
            return "F" + (glfwKey - GLFW.GLFW_KEY_F1 + 1);
        }
        if (glfwKey >= GLFW.GLFW_KEY_KP_0 && glfwKey <= GLFW.GLFW_KEY_KP_9) {
            return "Num " + (glfwKey - GLFW.GLFW_KEY_KP_0);
        }
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_SPACE -> "Leertaste";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "Shift links";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "Shift rechts";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "Strg links";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "Strg rechts";
            case GLFW.GLFW_KEY_LEFT_ALT -> "Alt links";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "Alt rechts";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "Feststelltaste";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_BACKSPACE -> "Rücktaste";
            case GLFW.GLFW_KEY_DELETE -> "Entf";
            case GLFW.GLFW_KEY_INSERT -> "Einfg";
            case GLFW.GLFW_KEY_HOME -> "Pos1";
            case GLFW.GLFW_KEY_END -> "Ende";
            case GLFW.GLFW_KEY_PAGE_UP -> "Bild auf";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "Bild ab";
            case GLFW.GLFW_KEY_UP -> "Pfeil hoch";
            case GLFW.GLFW_KEY_DOWN -> "Pfeil runter";
            case GLFW.GLFW_KEY_LEFT -> "Pfeil links";
            case GLFW.GLFW_KEY_RIGHT -> "Pfeil rechts";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW.GLFW_KEY_SEMICOLON -> ";";
            case GLFW.GLFW_KEY_APOSTROPHE -> "'";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_BACKSLASH -> "\\";
            case GLFW.GLFW_KEY_KP_ENTER -> "Num Enter";
            case GLFW.GLFW_KEY_KP_ADD -> "Num +";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "Num -";
            case GLFW.GLFW_KEY_KP_MULTIPLY -> "Num *";
            case GLFW.GLFW_KEY_KP_DIVIDE -> "Num /";
            default -> "Taste " + glfwKey;
        };
    }

    private KeyNames() {}
}
