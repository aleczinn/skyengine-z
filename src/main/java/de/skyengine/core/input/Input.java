package de.skyengine.core.input;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.Window;
import de.skyengine.core.input.controller.ControllerAxis;
import de.skyengine.core.input.controller.ControllerButton;
import de.skyengine.core.input.controller.GameController;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.utils.math.MathUtils;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Input {

    private final Logger logger = LogManager.getLogger(Input.class.getName());

    private static final int KEY_COUNT = GLFW.GLFW_KEY_LAST + 1;            // 349
    private static final int MOUSE_COUNT = GLFW.GLFW_MOUSE_BUTTON_LAST + 1; // 8

    private final Window window;

    private double mouseX = 0, mouseY = 0;
    private double lastMouseX, lastMouseY;
    private double deltaMouseX, deltaMouseY;
    private double scrollX = 0, scrollY = 0;

    private boolean cursorEntered = false;

    private final InputState[] keyStates = new InputState[KEY_COUNT];
    private final InputState[] mouseStates = new InputState[MOUSE_COUNT];

    /* Only the keys/buttons that changed since the last update get processed */
    private final int[] changedKeys = new int[KEY_COUNT];
    private int changedKeyCount = 0;
    private final int[] changedButtons = new int[MOUSE_COUNT];
    private int changedButtonCount = 0;

    private final Map<Integer, GameController> controller;

    public Input(Window window) {
        this.window = window;
        this.controller = new HashMap<>();

        Arrays.fill(this.keyStates, InputState.NONE);
        Arrays.fill(this.mouseStates, InputState.NONE);
    }

    public void init() {
        for (int i = GLFW.GLFW_JOYSTICK_1; i <= GLFW.GLFW_JOYSTICK_LAST; i++) {
            if (GLFW.glfwJoystickPresent(i)) {
                this.controller.put(i, new GameController(i));
                this.logger.info("Controller connected. (" + i + ", " + this.controller.get(i).getName() + ")");
            }
        }

        GLFW.glfwSetCursorEnterCallback(this.window.getWindowID(), this::onCursorEnter);
        GLFW.glfwSetCursorPosCallback(this.window.getWindowID(), this::onCursorPos);
        GLFW.glfwSetScrollCallback(this.window.getWindowID(), this::onScroll);
        GLFW.glfwSetMouseButtonCallback(this.window.getWindowID(), this::onMouseButton);
        GLFW.glfwSetKeyCallback(this.window.getWindowID(), this::onKey);
        GLFW.glfwSetJoystickCallback(this::onJoystick);
    }

    public void update() {
        this.deltaMouseX = this.mouseX - this.lastMouseX;
        this.deltaMouseY = this.mouseY - this.lastMouseY;

        this.lastMouseX = this.mouseX;
        this.lastMouseY = this.mouseY;

        this.scrollX = 0;
        this.scrollY = 0;

        /* Advance only changed keys: PRESSED -> DOWN, RELEASED -> NONE */
        for (int i = 0; i < this.changedKeyCount; i++) {
            int key = this.changedKeys[i];
            InputState state = this.keyStates[key];
            if (state == InputState.PRESSED) {
                this.keyStates[key] = InputState.DOWN;
            } else if (state == InputState.RELEASED) {
                this.keyStates[key] = InputState.NONE;
            }
        }
        this.changedKeyCount = 0;

        /* Same for mouse buttons */
        for (int i = 0; i < this.changedButtonCount; i++) {
            int button = this.changedButtons[i];
            InputState state = this.mouseStates[button];
            if (state == InputState.PRESSED) {
                this.mouseStates[button] = InputState.DOWN;
            } else if (state == InputState.RELEASED) {
                this.mouseStates[button] = InputState.NONE;
            }
        }
        this.changedButtonCount = 0;

        /* Update controller states */
        for (GameController controller : this.controller.values()) {
            controller.update();
        }
    }

    private void onCursorEnter(long window, boolean entered) {
        this.cursorEntered = entered;
    }

    private void onCursorPos(long window, double x, double y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    private void onScroll(long window, double xOffset, double yOffset) {
        this.scrollX = xOffset;
        this.scrollY = yOffset;
    }

    private void onMouseButton(long window, int button, int action, int mode) {
        if (button < 0 || button >= MOUSE_COUNT) return;

        if (action == GLFW.GLFW_PRESS) {
            this.mouseStates[button] = InputState.PRESSED;
            this.markButtonChanged(button);
        } else if (action == GLFW.GLFW_RELEASE) {
            this.mouseStates[button] = InputState.RELEASED;
            this.markButtonChanged(button);
        }
    }

    private void onKey(long window, int key, int scancode, int action, int mods) {
        if (key < 0 || key >= KEY_COUNT) return; // GLFW_KEY_UNKNOWN is -1

        if (action == GLFW.GLFW_PRESS) {
            this.keyStates[key] = InputState.PRESSED;
            this.markKeyChanged(key);
        } else if (action == GLFW.GLFW_RELEASE) {
            this.keyStates[key] = InputState.RELEASED;
            this.markKeyChanged(key);
        }
        /* GLFW_REPEAT is intentionally ignored - DOWN already covers held keys */
    }

    private void markKeyChanged(int key) {
        if (this.changedKeyCount < this.changedKeys.length) {
            this.changedKeys[this.changedKeyCount++] = key;
        }
    }

    private void markButtonChanged(int button) {
        if (this.changedButtonCount < this.changedButtons.length) {
            this.changedButtons[this.changedButtonCount++] = button;
        }
    }

    private void onJoystick(int joystickId, int event) {
        if (event == GLFW.GLFW_CONNECTED) {
            this.controller.put(joystickId, new GameController(joystickId));
            this.logger.info("Controller connected. (" + joystickId + ", " + this.controller.get(joystickId).getName() + ")");
        } else if (event == GLFW.GLFW_DISCONNECTED) {
            GameController removed = this.controller.remove(joystickId);
            this.logger.info("Controller disconnected. (" + joystickId + (removed != null ? ", " + removed.getName() : "") + ")");
        }
    }

    /** Returns whether the button is currently pressed */
    public boolean isMouseDown(int button) {
        if (button < 0 || button >= MOUSE_COUNT) return false;
        InputState state = this.mouseStates[button];
        return state == InputState.PRESSED || state == InputState.DOWN;
    }

    /** Returns whether the button <b>was</b> <i>pressed</i> */
    public boolean isMousePressed(int button) {
        return button >= 0 && button < MOUSE_COUNT && this.mouseStates[button] == InputState.PRESSED;
    }

    /** Returns whether the button <b>was</b> <i>released</i> */
    public boolean isMouseReleased(int button) {
        return button >= 0 && button < MOUSE_COUNT && this.mouseStates[button] == InputState.RELEASED;
    }

    /** Returns whether the key is currently pressed */
    public boolean isKeyDown(int key) {
        if (key < 0 || key >= KEY_COUNT) return false;
        InputState state = this.keyStates[key];
        return state == InputState.PRESSED || state == InputState.DOWN;
    }

    /** Returns whether the key <b>was</b> <i>pressed</i> */
    public boolean isKeyPressed(int key) {
        return key >= 0 && key < KEY_COUNT && this.keyStates[key] == InputState.PRESSED;
    }

    /** Returns whether the key <b>was</b> <i>released</i> */
    public boolean isKeyReleased(int key) {
        return key >= 0 && key < KEY_COUNT && this.keyStates[key] == InputState.RELEASED;
    }

    /** Return the first connected controller or null if nothing is connected */
    public GameController getFirstController() {
        if (this.controller.isEmpty()) return null;
        return this.controller.values().iterator().next();
    }

    /** Return a specific controller from id */
    public GameController getController(int controllerId) {
        return this.controller.get(controllerId);
    }

    /** Check if a controller is connected */
    public boolean isControllerConnected() {
        return !this.controller.isEmpty();
    }

    /** Returns whether the button is currently pressed */
    public boolean isControllerButtonDown(ControllerButton button) {
        // TODO
        return false;
    }

    /** Returns whether the button <b>was</b> <i>pressed</i> */
    public boolean isControllerButtonPressed(ControllerButton button) {
        // TODO
        return false;
    }

    /** Returns whether the button <b>was</b> <i>released</i> */
    public boolean isControllerButtonReleased(ControllerButton button) {
        // TODO
        return false;
    }

    /** Return the axis value */
    public float getControllerAxis(ControllerAxis axis) {
        // TODO
        return 0.0f;
    }

    /**
     * Calculates a value out of every connected controller and the keyboard input.
     * The default keys are WASD!
     *
     * @return a value between -1 and 1 for the axis.
     */
    public double getAxis(InputAxis axis) {
        double keyboardValue = 0.0;
        double controllerValue = 0.0;

        // Keyboard
        switch (axis) {
            case HORIZONTAL -> {
                float left = this.isKeyDown(GLFW.GLFW_KEY_A) ? -1 : 0;
                float right = this.isKeyDown(GLFW.GLFW_KEY_D) ? 1 : 0;
                keyboardValue = MathUtils.clamp(left + right, -1, 1);
            }
            case VERTICAL -> {
                float up = this.isKeyDown(GLFW.GLFW_KEY_W) ? -1 : 0;
                float down = this.isKeyDown(GLFW.GLFW_KEY_S) ? 1 : 0;
                keyboardValue = MathUtils.clamp(up + down, -1, 1);
            }
        }

        // Controller
        switch (axis) {
            case HORIZONTAL -> controllerValue = this.getControllerAxis(ControllerAxis.LEFT_X);
            case VERTICAL -> controllerValue = this.getControllerAxis(ControllerAxis.LEFT_Y);
        }

        return Math.abs(keyboardValue) > Math.abs(controllerValue) ? keyboardValue : controllerValue;
    }

    public void showCursor() {
        GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }

    public void hideCursor() {
        GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
    }

    public void disableCursor() {
        GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    public void centerMouse() {
        GLFW.glfwSetCursorPos(this.window.getWindowID(), SkyEngine.get().getWindow().getWidth() / 2D, SkyEngine.get().getWindow().getHeight() / 2D);
    }

    public String getClipboard() {
        return GLFW.glfwGetClipboardString(this.window.getWindowID());
    }

    public void setClipboard(String text) {
        GLFW.glfwSetClipboardString(this.window.getWindowID(), text);
    }

    public double getMouseX() {
        return mouseX;
    }

    public double getMouseY() {
        return mouseY;
    }

    public double getLastMouseX() {
        return lastMouseX;
    }

    public double getLastMouseY() {
        return lastMouseY;
    }

    public double getDeltaMouseX() {
        return deltaMouseX;
    }

    public double getDeltaMouseY() {
        return deltaMouseY;
    }

    public double getScrollX() {
        return scrollX;
    }

    public double getScrollY() {
        return scrollY;
    }

    public boolean isCursorEntered() {
        return cursorEntered;
    }
}