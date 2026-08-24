package de.skyengine.graphics.gui.widget;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SliderTest {

    @Test
    void validDragRequestsExactlyOneClickOnRelease() {
        AtomicInteger releases = new AtomicInteger();
        Slider slider = new Slider(100, 20, 0, 100, 1, 50,
                value -> Integer.toString((int) value), value -> {}, releases::incrementAndGet);
        slider.layoutAt(10, 10);

        assertTrue(slider.mousePressed(50, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(slider.mouseReleased(200, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(slider.mouseReleased(200, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(releases.get() == 1);
    }

    @Test
    void invalidPressOrWrongReleaseButtonNeverRequestsAClick() {
        Slider slider = new Slider(100, 20, 0, 100, 1, 50,
                value -> Integer.toString((int) value), value -> {}, null);
        slider.layoutAt(10, 10);

        assertFalse(slider.mousePressed(200, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(slider.mouseReleased(200, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(slider.mousePressed(50, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(slider.mouseReleased(50, 15, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        assertTrue(slider.mouseReleased(50, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT));
    }
}
