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

import java.util.HashMap;
import java.util.Map;

public class Input {

    private final Logger logger = LogManager.getLogger(Input.class.getName());

    private final Window window;

    private double mouseX = 0, mouseY = 0;
    private double lastMouseX, lastMouseY;
    private double deltaMouseX, deltaMouseY;
    private double scrollX = 0, scrollY = 0;

    private boolean cursorEntered = false;

    private final HashMap<Integer, InputState> mouseStates;
    private final HashMap<Integer, InputState> keyStates;

    private final Map<Integer, GameController> controller;

    public Input(Window window) {
        this.window = window;

        this.mouseStates = new HashMap<>();
        this.keyStates = new HashMap<>();
        this.controller = new HashMap<>();
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

        /* Update mouse states */
        this.mouseStates.forEach((key, value) -> {
            if (value == InputState.PRESSED) {
                this.mouseStates.put(key, InputState.DOWN);
            }
            if (value == InputState.RELEASED) {
                this.mouseStates.put(key, InputState.NONE);
            }
        });

        /* Update key states */
        this.keyStates.forEach((key, value) -> {
            if (value == InputState.PRESSED) {
                this.keyStates.put(key, InputState.DOWN);
            }
            if (value == InputState.RELEASED) {
                this.keyStates.put(key, InputState.NONE);
            }
        });

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
        switch (action) {
            case GLFW.GLFW_PRESS -> this.mouseStates.put(button, InputState.PRESSED);
            case GLFW.GLFW_RELEASE -> this.mouseStates.put(button, InputState.RELEASED);
        }
    }

    private void onKey(long window, int key, int scancode, int action, int mods) {
        switch (action) {
            case GLFW.GLFW_PRESS -> this.keyStates.put(key, InputState.PRESSED);
            case GLFW.GLFW_RELEASE -> this.keyStates.put(key, InputState.RELEASED);
        }
    }

    private void onJoystick(int joystickId, int event) {
        if (event == GLFW.GLFW_CONNECTED) {
            this.controller.put(joystickId, new GameController(joystickId));
            this.logger.info("Controller connected. (" + joystickId + ", " + this.controller.get(joystickId).getName() + ")");
        } else if (event == GLFW.GLFW_DISCONNECTED) {
            this.logger.info("Controller disconnected. (" + joystickId + ", " + this.controller.get(joystickId).getName() + ")");
            this.controller.remove(joystickId);
        }
    }

    /** Returns whether the button is currently pressed */
    public boolean isMouseDown(int button) {
        InputState state = this.mouseStates.get(button);
        return state == InputState.PRESSED || state == InputState.DOWN;
    }

    /** Returns whether the button <b>was</b> <i>pressed</i> */
    public boolean isMousePressed(int button) {
        return this.mouseStates.get(button) == InputState.PRESSED;
    }

    /** Returns whether the button <b>was</b> <i>released</i> */
    public boolean isMouseReleased(int button) {
        return this.mouseStates.get(button) == InputState.RELEASED;
    }

    /** Returns whether the key is currently pressed */
    public boolean isKeyDown(int key) {
        InputState state = this.keyStates.get(key);
        return state == InputState.PRESSED || state == InputState.DOWN;
    }

    /** Returns whether the key <b>was</b> <i>pressed</i> */
    public boolean isKeyPressed(int key) {
        return this.keyStates.get(key) == InputState.PRESSED;
    }

    /** Returns whether the key <b>was</b> <i>released</i> */
    public boolean isKeyReleased(int key) {
        return this.keyStates.get(key) == InputState.RELEASED;
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
