package de.skyengine.core.input.controller;

import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class GameController {

    private final int id;
    private final String name;
    private final boolean isGamepad;

    private FloatBuffer axesBuffer;
    private ByteBuffer buttonsBuffer;

    private float[] axes;
    private byte[] buttons;
    private byte[] lastButtons;

    public GameController(int id) {
        this.id = id;
        this.name = GLFW.glfwGetJoystickName(id);
        this.isGamepad = GLFW.glfwJoystickIsGamepad(id);

        this.update();
    }

    public void update() {
        // Handle axis
        this.axesBuffer = GLFW.glfwGetJoystickAxes(id);
        if (this.axesBuffer != null) {
            this.axes = new float[this.axesBuffer.limit()];
            for (int i = 0; i < this.axes.length; i++) {
                this.axes[i] = this.axesBuffer.get(i);
            }
        }

        // Handle buttons
        if (this.buttons != null) {
            this.lastButtons = this.buttons.clone();
        }

        this.buttonsBuffer = GLFW.glfwGetJoystickButtons(id);
        if (this.buttonsBuffer != null) {
            this.buttons = new byte[this.buttonsBuffer.limit()];
            for (int i = 0; i < this.buttons.length; i++) {
                this.buttons[i] = this.buttonsBuffer.get(i);
            }

            if (this.lastButtons == null) {
                this.lastButtons = new byte[this.buttons.length];
            }
        }
    }

    public boolean isButtonPressed(int button) {
        if (this.buttons == null || button >= this.buttons.length) return false;
        return this.buttons[button] == GLFW.GLFW_PRESS && (this.lastButtons.length <= button || this.lastButtons[button] == GLFW.GLFW_RELEASE);
    }

    public boolean isButtonDown(int button) {
        if (this.buttons == null || button >= this.buttons.length) return false;
        return this.buttons[button] == GLFW.GLFW_PRESS;
    }

    public boolean isButtonReleased(int button) {
        if (this.buttons == null || button >= this.buttons.length) return false;
        return this.buttons[button] == GLFW.GLFW_RELEASE && this.lastButtons.length > button && this.lastButtons[button] == GLFW.GLFW_PRESS;
    }

    public float getAxis(int axis) {
        if (this.axes == null || axis >= this.axes.length) return 0.0f;
        return this.axes[axis];
    }

    public boolean isConnected() {
        return GLFW.glfwJoystickPresent(id);
    }

    public boolean isGamepad() {
        return isGamepad;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
