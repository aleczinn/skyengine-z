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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Input system with a single-producer/single-consumer event queue.
 * GLFW callbacks (main thread) only enqueue events - all state lives on
 * the render thread and is frozen for the duration of a frame.
 * <p>
 * <b>Wie das funktioniert:</b>
 * Producer/Consumer-Trennung: Die GLFW-Callbacks (Main-Thread) fassen die keyStates/mouseStates-Arrays nie mehr an – sie kodieren das Event in einen int (Type-Bit, Press/Release-Bit, Keycode) und legen ihn in den Ring-Buffer. Der Render-Thread leert die Queue einmal pro Frame in update() und wendet alles auf seine privaten Arrays an. Danach ist der Zustand bis zum nächsten Frame eingefroren.
 * Warum der Ring-Buffer thread-sicher ist: Klassisches SPSC-Muster. Der Producer schreibt erst den Array-Slot, dann den queueTail (volatile write) – wer den neuen Tail sieht, sieht garantiert auch die Slot-Daten (happens-before). Da nur ein Thread schreibt und nur einer liest, braucht es weder Locks noch CAS-Schleifen. Null Allokationen, null Contention.
 * Maus & Scroll: Cursor-Position kommt als volatile double rein (atomar laut Java-Spec, kein Tearing) und wird in update() einmalig gesnapshottet – deltaMouseX/Y ist damit pro Frame konsistent. Scroll wird akkumuliert statt überschrieben: Wenn zwischen zwei Frames zwei Scroll-Events kommen, ging in deiner alten Version eins verloren; jetzt addieren sie sich.
 * Ein bewusster Trade-off, den du kennen solltest: Wenn Press und Release derselben Taste innerhalb eines einzigen Frames eintreffen (physisch nur bei extrem kurzen Klicks bei niedriger FPS möglich), gewinnt das Release – isKeyPressed ist in dem Frame false, der Klick "verschluckt". Das war in der alten Version genauso. Falls dich das später bei schnellen PvP-Klicks stört, lässt sich das in der Drain-Schleife nachrüsten (Release auf bereits-PRESSED-Keys um einen Frame verzögern)
 */
public class Input {

    private final Logger logger = LogManager.getLogger(Input.class.getName());

    private static final int KEY_COUNT = GLFW.GLFW_KEY_LAST + 1;            // 349
    private static final int MOUSE_COUNT = GLFW.GLFW_MOUSE_BUTTON_LAST + 1; // 8

    /* --- Event encoding: [bit 17: type] [bit 16: action] [bits 0-15: code] --- */
    private static final int TYPE_MOUSE = 1 << 17;
    private static final int ACTION_PRESS = 1 << 16;
    private static final int CODE_MASK = 0xFFFF;

    /* --- SPSC ring buffer (power of two!) --- */
    private static final int QUEUE_CAPACITY = 256;
    private static final int QUEUE_MASK = QUEUE_CAPACITY - 1;
    private final int[] eventQueue = new int[QUEUE_CAPACITY];
    private final AtomicInteger queueTail = new AtomicInteger(); // written by main thread
    private final AtomicInteger queueHead = new AtomicInteger(); // written by render thread

    private final Window window;

    /* Written by callbacks (main thread), read by render thread.
       volatile double reads/writes are atomic per JLS - no tearing. */
    private volatile double rawMouseX = 0, rawMouseY = 0;
    private volatile boolean cursorEntered = false;

    /* Scroll accumulates between frames - tiny lock, events are rare */
    private final Object scrollLock = new Object();
    private double pendingScrollX = 0, pendingScrollY = 0;

    /* --- Everything below is ONLY touched by the render thread --- */
    private double mouseX = 0, mouseY = 0;
    private double lastMouseX, lastMouseY;
    private double deltaMouseX, deltaMouseY;
    private double scrollX = 0, scrollY = 0;

    private final InputState[] keyStates = new InputState[KEY_COUNT];
    private final InputState[] mouseStates = new InputState[MOUSE_COUNT];

    /* Keys/buttons that changed last frame and need PRESSED->DOWN / RELEASED->NONE */
    private final int[] changedKeys = new int[QUEUE_CAPACITY];
    private int changedKeyCount = 0;
    private final int[] changedButtons = new int[QUEUE_CAPACITY];
    private int changedButtonCount = 0;

    /* Volatile, weil es auch vom Main-Thread (Fullscreen-Toggle) gesetzt wird */
    private volatile boolean resetMouseDelta = true;

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

    /**
     * Called once per frame on the render thread BEFORE game logic.
     * After this returns, the input state is stable until the next call.
     */
    public void update() {
        /* 1. Advance last frame's edges: PRESSED -> DOWN, RELEASED -> NONE */
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

        /* 2. Drain the event queue and apply this frame's events */
        int head = this.queueHead.get();
        int tail = this.queueTail.get(); // volatile read - sees all events published before it
        while (head != tail) {
            int event = this.eventQueue[head & QUEUE_MASK];
            head++;

            int code = event & CODE_MASK;
            boolean press = (event & ACTION_PRESS) != 0;

            if ((event & TYPE_MOUSE) != 0) {
                this.mouseStates[code] = press ? InputState.PRESSED : InputState.RELEASED;
                if (this.changedButtonCount < this.changedButtons.length) {
                    this.changedButtons[this.changedButtonCount++] = code;
                }
            } else {
                this.keyStates[code] = press ? InputState.PRESSED : InputState.RELEASED;
                if (this.changedKeyCount < this.changedKeys.length) {
                    this.changedKeys[this.changedKeyCount++] = code;
                }
            }
        }
        this.queueHead.set(head);

        /* 3. Snapshot mouse position & scroll for this frame */
        this.mouseX = this.rawMouseX;
        this.mouseY = this.rawMouseY;

        /* Cursor wurde repositioniert (erster Frame, Fullscreen-Toggle, etc.) -> Delta dieses Frames verwerfen */
        if (this.resetMouseDelta) {
            this.lastMouseX = this.mouseX;
            this.lastMouseY = this.mouseY;
            this.resetMouseDelta = false;
        }

        this.deltaMouseX = this.mouseX - this.lastMouseX;
        this.deltaMouseY = this.mouseY - this.lastMouseY;
        this.lastMouseX = this.mouseX;
        this.lastMouseY = this.mouseY;

        synchronized (this.scrollLock) {
            this.scrollX = this.pendingScrollX;
            this.scrollY = this.pendingScrollY;
            this.pendingScrollX = 0;
            this.pendingScrollY = 0;
        }

        /* 4. Controller states */
        for (GameController controller : this.controller.values()) {
            controller.update();
        }
    }

    /* --- Producer side (main thread, GLFW callbacks) --- */

    private void enqueue(int event) {
        int tail = this.queueTail.get();
        if (tail - this.queueHead.get() >= QUEUE_CAPACITY) {
            return; // queue full (would need >256 events in one frame) - drop instead of corrupting
        }
        this.eventQueue[tail & QUEUE_MASK] = event;
        this.queueTail.set(tail + 1); // volatile write publishes the slot to the consumer
    }

    private void onKey(long window, int key, int scancode, int action, int mods) {
        if (key < 0 || key >= KEY_COUNT) return; // GLFW_KEY_UNKNOWN is -1

        if (action == GLFW.GLFW_PRESS) {
            this.enqueue(key | ACTION_PRESS);
        } else if (action == GLFW.GLFW_RELEASE) {
            this.enqueue(key);
        }
        /* GLFW_REPEAT intentionally ignored - DOWN covers held keys */
    }

    private void onMouseButton(long window, int button, int action, int mode) {
        if (button < 0 || button >= MOUSE_COUNT) return;

        if (action == GLFW.GLFW_PRESS) {
            this.enqueue(button | TYPE_MOUSE | ACTION_PRESS);
        } else if (action == GLFW.GLFW_RELEASE) {
            this.enqueue(button | TYPE_MOUSE);
        }
    }

    private void onCursorEnter(long window, boolean entered) {
        this.cursorEntered = entered;
    }

    private void onCursorPos(long window, double x, double y) {
        this.rawMouseX = x;
        this.rawMouseY = y;
    }

    private void onScroll(long window, double xOffset, double yOffset) {
        synchronized (this.scrollLock) {
            this.pendingScrollX += xOffset;
            this.pendingScrollY += yOffset;
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

    /* --- Queries (render thread) - plain array reads --- */

    /** Returns whether the button is currently pressed */
    public boolean isMouseDown(int button) {
        if (button < 0 || button >= MOUSE_COUNT) return false;
        InputState state = this.mouseStates[button];
        return state == InputState.PRESSED || state == InputState.DOWN;
    }

    /** Returns whether the button <b>was</b> <i>pressed</i> this frame */
    public boolean isMousePressed(int button) {
        return button >= 0 && button < MOUSE_COUNT && this.mouseStates[button] == InputState.PRESSED;
    }

    /** Returns whether the button <b>was</b> <i>released</i> this frame */
    public boolean isMouseReleased(int button) {
        return button >= 0 && button < MOUSE_COUNT && this.mouseStates[button] == InputState.RELEASED;
    }

    /** Returns whether the key is currently pressed */
    public boolean isKeyDown(int key) {
        if (key < 0 || key >= KEY_COUNT) return false;
        InputState state = this.keyStates[key];
        return state == InputState.PRESSED || state == InputState.DOWN;
    }

    /** Returns whether the key <b>was</b> <i>pressed</i> this frame */
    public boolean isKeyPressed(int key) {
        return key >= 0 && key < KEY_COUNT && this.keyStates[key] == InputState.PRESSED;
    }

    /** Returns whether the key <b>was</b> <i>released</i> this frame */
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

        switch (axis) {
            case HORIZONTAL -> controllerValue = this.getControllerAxis(ControllerAxis.LEFT_X);
            case VERTICAL -> controllerValue = this.getControllerAxis(ControllerAxis.LEFT_Y);
        }

        return Math.abs(keyboardValue) > Math.abs(controllerValue) ? keyboardValue : controllerValue;
    }

    public void disableCursor() {
        GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        if (GLFW.glfwRawMouseMotionSupported()) {
            GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
        }
        this.resetMouseDelta();
    }

    public void showCursor() {
        GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        this.resetMouseDelta();
    }

    public void centerMouse() {
        GLFW.glfwSetCursorPos(this.window.getWindowID(), SkyEngine.get().getWindow().getWidth() / 2D, SkyEngine.get().getWindow().getHeight() / 2D);
        this.resetMouseDelta();
    }

    public void hideCursor() {
        GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
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

    /**
     * Verwirft das Mouse-Delta des nächsten Frames. Aufrufen, wann immer der
     * Cursor "teleportiert": Cursor-Modus-Wechsel, Fenstermodus-Wechsel, centerMouse.
     */
    public void resetMouseDelta() {
        this.resetMouseDelta = true;
    }
}