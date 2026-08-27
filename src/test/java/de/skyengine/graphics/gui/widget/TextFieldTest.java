package de.skyengine.graphics.gui.widget;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TextFieldTest {
    @Test
    void configuredRightClickClearsSearchText() {
        TextField field = new TextField(100, 16, 64, null)
                .text("suchtext").clearOnRightClick();
        field.x = 10;
        field.y = 20;

        assertTrue(field.mousePressed(15, 25, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        assertEquals("", field.getText());
    }

    @Test
    void ordinaryTextFieldKeepsTextOnRightClick() {
        TextField field = new TextField(100, 16, 64, null).text("bleibt");
        assertFalse(field.mousePressed(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        assertEquals("bleibt", field.getText());
    }
}
