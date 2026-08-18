package de.skyengine.core.input;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InputConsumptionTest {

    @Test
    void consumedPressRemainsDownButCannotTriggerAnotherBinding() throws Exception {
        Input input = new Input(null);
        states(input)[GLFW.GLFW_KEY_G] = InputState.PRESSED;

        assertTrue(input.consumeKeyPress(GLFW.GLFW_KEY_G));
        assertFalse(input.isKeyPressed(GLFW.GLFW_KEY_G));
        assertFalse(input.isBindPressed(GLFW.GLFW_KEY_G));
        assertTrue(input.isKeyDown(GLFW.GLFW_KEY_G));
        assertFalse(input.consumeKeyPress(GLFW.GLFW_KEY_G));
    }

    private static InputState[] states(Input input) throws Exception {
        Field field = Input.class.getDeclaredField("keyStates");
        field.setAccessible(true);
        return (InputState[]) field.get(input);
    }
}
