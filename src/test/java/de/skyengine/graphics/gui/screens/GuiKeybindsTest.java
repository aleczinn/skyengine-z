package de.skyengine.graphics.gui.screens;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GuiKeybindsTest {

    @Test
    void escapeClearsCapturedBindingLikeMinecraft() {
        assertEquals(GLFW.GLFW_KEY_UNKNOWN, GuiKeybinds.capturedBinding(GLFW.GLFW_KEY_ESCAPE));
    }

    @Test
    void otherCapturedKeysRemainUnchanged() {
        assertEquals(GLFW.GLFW_KEY_R, GuiKeybinds.capturedBinding(GLFW.GLFW_KEY_R));
    }
}
