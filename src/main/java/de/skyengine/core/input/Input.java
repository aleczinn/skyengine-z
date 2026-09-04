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

    /** Leere Eingabe (keine Taste gedrückt, keine Mausbewegung) — für die Spieler-Physik bei
     *  offenem Container-GUI. Wird nie {@code update()}t, die States bleiben dauerhaft NONE. */
    public static final Input EMPTY = new Input(null);

    private final Logger logger = LogManager.getLogger(Input.class.getName());

    private static final int KEY_COUNT = GLFW.GLFW_KEY_LAST + 1;            // 349
    private static final int MOUSE_COUNT = GLFW.GLFW_MOUSE_BUTTON_LAST + 1; // 8

    /* --- Event encoding: [Bits 31-30: Typ (0=KEY, 1=MOUSE, 2=CHAR)] [Bit 29: Press] [Bits 0-20: Code] ---
       21 Code-Bits reichen für GLFW-Keycodes (<= 348) UND Unicode-Codepoints (<= 0x10FFFF). */
    private static final int TYPE_MOUSE = 1 << 30;
    private static final int TYPE_CHAR = 2 << 30;
    private static final int TYPE_MASK = 3 << 30;
    private static final int ACTION_PRESS = 1 << 29;
    private static final int CODE_MASK = 0x1FFFFF;

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

    /* Text-Eingabe (glfwSetCharCallback): Unicode-Codepoints dieses Frames, in Event-Reihenfolge
       relativ zu den Key-Events (gleiche Queue) - wichtig für Textfelder (Backspace vs. Zeichen). */
    private static final int MAX_CHARS_PER_FRAME = 64;
    private final int[] charsTyped = new int[MAX_CHARS_PER_FRAME];
    private int charCount = 0;

    /* Teleport-Behandlung, zwei Teile:
       - warpPending setzt der Window-Thread VOR einem Cursor-Sprung (Modus-/Fenstermodus-Wechsel,
         Zentrieren). Es ist eine Ankündigung, kein Token.
       - warpSeq erhöht erst der onCursorPos-Callback, wenn die Position NACH dem Sprung wirklich
         eingetroffen ist; der Render-Thread merkt sich in seenWarpSeq den quittierten Stand.
       Die Trennung ist der Kern: der Moduswechsel läuft in einem Main-Thread-Task, die dadurch
       ausgelöste Cursor-Meldung kommt aber erst im nächsten glfwWaitEvents-Durchlauf. Würde der
       Zähler schon beim Ausführen des Tasks steigen, quittierte der Render-Thread ihn binnen
       ~1,4 ms (700 FPS) mit der ALTEN Position — und der echte Sprung käme danach ungeschützt an
       und riss die Kamera weg. */
    private volatile boolean warpPending = true;
    private final AtomicInteger warpSeq = new AtomicInteger(1);
    private int seenWarpSeq = 0;   // nur Render-Thread

    /* Ist der Cursor PHYSISCH gefangen? Der GuiManager kennt nur seinen eigenen Zustand, der
       Moduswechsel läuft aber deferiert — dazwischen ist das GUI schon zu, der Cursor aber noch
       frei, und seine Bewegung darf noch nicht den Blick drehen. */
    private volatile boolean cursorGrabbed = false;
    /* Erst true, wenn GLFW die erste echte Cursorposition geliefert hat.
      Vorher darf der Warp-Stand nicht als verarbeitet gelten. */
    private volatile boolean cursorInitialized = false;

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
        GLFW.glfwSetWindowFocusCallback(this.window.getWindowID(), this::onWindowFocus);
        GLFW.glfwSetScrollCallback(this.window.getWindowID(), this::onScroll);
        GLFW.glfwSetMouseButtonCallback(this.window.getWindowID(), this::onMouseButton);
        GLFW.glfwSetKeyCallback(this.window.getWindowID(), this::onKey);
        GLFW.glfwSetCharCallback(this.window.getWindowID(), this::onChar);
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
        this.charCount = 0;
        int head = this.queueHead.get();
        int tail = this.queueTail.get(); // volatile read - sees all events published before it
        while (head != tail) {
            int event = this.eventQueue[head & QUEUE_MASK];
            head++;

            int type = event & TYPE_MASK;
            int code = event & CODE_MASK;
            boolean press = (event & ACTION_PRESS) != 0;

            if (type == TYPE_CHAR) {
                if (this.charCount < this.charsTyped.length) {
                    this.charsTyped[this.charCount++] = code;
                }
            } else if (type == TYPE_MOUSE) {
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

        /* Cursor wurde repositioniert (erster Frame, GUI auf/zu, Fullscreen-Toggle) -> Delta
           dieses Frames verwerfen.

           REIHENFOLGE IST TRAGEND: der Zähler wird NACH der Position gelesen, und der Callback
           erhöht ihn VOR dem Schreiben der Position. Sieht dieser Frame also eine Position von
           nach dem Sprung, dann ist über die volatile-Kette zwingend auch der erhöhte Zähler
           sichtbar -> wir verwerfen. Sieht er umgekehrt die alte Position bei schon erhöhtem
           Zähler, verwirft er einen harmlosen Frame echter Mausbewegung. Liest man den Zähler VOR
           der Position, ist die Lücke wieder offen. */
        int seq = this.warpSeq.get();
        boolean warped = seq != this.seenWarpSeq || !this.cursorInitialized;
        if (warped) {
            this.lastMouseX = this.mouseX;
            this.lastMouseY = this.mouseY;
            /* Erst als verarbeitet markieren, wenn eine ECHTE Cursorposition bekannt ist —
               sonst verpufft der Reset auf (0,0) vor dem ersten Callback. */
            if (this.cursorInitialized) {
                this.seenWarpSeq = seq;
            }
        }

        double dx = this.mouseX - this.lastMouseX;
        double dy = this.mouseY - this.lastMouseY;

        /* Sicherheitsnetz gegen Cursor-Sprünge, die keine der angekündigten Quellen war. Ein Delta
           von mehr als einem Viertel der kürzeren Fensterkante in EINEM Frame ist keine Maus: bei
           den hier üblichen Bildraten bewegt sich eine reale Maus wenige Pixel. Verworfen, nicht
           geklemmt — ein halber Sprung wäre genauso falsch, nur unauffälliger. */
        double limit = Math.min(this.window.getWidth(), this.window.getHeight()) * 0.25;
        if (!warped && limit > 0 && (Math.abs(dx) > limit || Math.abs(dy) > limit)) {
            this.logger.warning(String.format(java.util.Locale.ROOT,
                    "Maus-Sprung verworfen: d=(%.1f, %.1f) limit=%.1f  von (%.1f, %.1f) nach (%.1f, %.1f)  "
                            + "warpSeq=%d seen=%d pending=%b grabbed=%b",
                    dx, dy, limit, this.lastMouseX, this.lastMouseY, this.mouseX, this.mouseY,
                    seq, this.seenWarpSeq, this.warpPending, this.cursorGrabbed));
            dx = 0;
            dy = 0;
        }

        this.deltaMouseX = dx;
        this.deltaMouseY = dy;
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

    private void onChar(long window, int codepoint) {
        this.enqueue((codepoint & CODE_MASK) | TYPE_CHAR);
    }

    private void onCursorEnter(long window, boolean entered) {
        this.cursorEntered = entered;
    }

    /**
     * Alt-Tab: während das Fenster den Fokus verliert, gibt GLFW einen gefangenen Cursor frei und
     * fängt ihn beim Zurückkommen erneut — beides Cursor-Sprünge, die sonst als echte Mausbewegung
     * durchgingen. Ankündigen wie bei jedem anderen Teleport; verworfen wird bei der ersten
     * Positionsmeldung danach.
     */
    private void onWindowFocus(long window, boolean focused) {
        this.resetMouseDelta();
    }

    private void onCursorPos(long window, double x, double y) {
        /* Erste Meldung nach einem angekündigten Teleport: JETZT das Token ausgeben. Zwingend VOR
           den Positionen — der Render-Thread liest erst die Position und dann den Zähler, sieht er
           also die gesprungene Position, ist der erhöhte Zähler über die volatile-Kette garantiert
           mit sichtbar. Callback und Teleport laufen beide auf dem Window-Thread, die Reihenfolge
           Ankündigung -> Sprung -> Meldung ist damit strikt. */
        if (this.warpPending) {
            this.warpPending = false;
            this.warpSeq.incrementAndGet();
        }
        this.rawMouseX = x;
        this.rawMouseY = y;
        this.cursorInitialized = true;
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

    /**
     * Verbraucht die Press-Flanke einer Taste, lässt ihren gehaltenen Zustand aber bestehen.
     * Gedacht für priorisierte Tastenkombinationen: nach erfolgreichem Consume sehen spätere
     * {@link #isKeyPressed}-/Keybind-Abfragen denselben Tastendruck nicht ein zweites Mal.
     */
    public boolean consumeKeyPress(int key) {
        if (!this.isKeyPressed(key)) return false;
        this.keyStates[key] = InputState.DOWN;
        return true;
    }

    /** Returns whether the key <b>was</b> <i>released</i> this frame */
    public boolean isKeyReleased(int key) {
        return key >= 0 && key < KEY_COUNT && this.keyStates[key] == InputState.RELEASED;
    }

    /** Ist eine der beiden STRG-Tasten gehalten (Modifier für Stapel-Aktionen)? */
    public boolean isCtrlDown() {
        return this.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || this.isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    /** Ist eine der beiden UMSCHALT-Tasten gehalten? Der Modifier, NICHT der Sneak-Keybind. */
    public boolean isShiftDown() {
        return this.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || this.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /* --- Keybinds (Taste ODER Maustaste) ---
       Ein Bind-Code ist ein GLFW-Key-Code, oder — ab MOUSE_OFFSET — eine Maustaste
       (Code = MOUSE_OFFSET + GLFW-Mouse-Button). GLFW-Keys reichen nur bis ~348, der Offset
       liegt weit darüber → keine Kollision. */
    public static final int MOUSE_OFFSET = 1000;

    /** Encodiert eine GLFW-Maustaste als Keybind-Code. */
    public static int mouseBind(int button) {
        return MOUSE_OFFSET + button;
    }

    /** true, wenn der Bind-Code eine Maustaste ist (statt einer Tastatur-Taste). */
    public static boolean isMouseBind(int code) {
        return code >= MOUSE_OFFSET;
    }

    /** Flanke (dieser Frame) für einen Bind — dispatcht auf Taste oder Maustaste. */
    public boolean isBindPressed(int code) {
        return isMouseBind(code) ? this.isMousePressed(code - MOUSE_OFFSET) : this.isKeyPressed(code);
    }

    /** Consumes one keybind press edge while preserving its held/down state. */
    public boolean consumeBindPress(int code) {
        if (!isMouseBind(code)) return this.consumeKeyPress(code);
        int button = code - MOUSE_OFFSET;
        if (!this.isMousePressed(button)) return false;
        this.mouseStates[button] = InputState.DOWN;
        return true;
    }

    /** Gehalten für einen Bind — dispatcht auf Taste oder Maustaste. */
    public boolean isBindDown(int code) {
        return isMouseBind(code) ? this.isMouseDown(code - MOUSE_OFFSET) : this.isKeyDown(code);
    }

    /** Ruft den Consumer für jede Taste auf, die in diesem Frame frisch gedrückt wurde. */
    public void forEachKeyPressedThisFrame(java.util.function.IntConsumer consumer) {
        for (int i = 0; i < this.changedKeyCount; i++) {
            int key = this.changedKeys[i];
            if (this.keyStates[key] == InputState.PRESSED) {
                consumer.accept(key);
            }
        }
    }

    /** Anzahl der in diesem Frame getippten Unicode-Zeichen (Textfelder). */
    public int charCount() {
        return this.charCount;
    }

    /** Unicode-Codepoint Nr. {@code index} dieses Frames (0 <= index < {@link #charCount()}). */
    public int charAt(int index) {
        return this.charsTyped[index];
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

    /*
     * WICHTIG: glfwSetInputMode(GLFW_CURSOR, ...) und glfwSetCursorPos MÜSSEN auf dem Main-Thread
     * (dem Fenster-/Event-Thread, der runWindowProcessLoop fährt) laufen — nicht auf dem Render-Thread,
     * von dem aus die Game-Loop diese Methoden aufruft. Unter Windows fesselt CURSOR_DISABLED den Cursor
     * via ClipCursor an die Fenstermitte; das Freigeben (CURSOR_NORMAL -> ClipCursor(NULL)) greift nur,
     * wenn es auf dem Message-Thread passiert. Vom Render-Thread aus wird der Cursor zwar sichtbar, bleibt
     * aber mittig „gefangen". Daher über die Main-Thread-Queue deferieren (weckt via glfwPostEmptyEvent),
     * genau wie der Fullscreen-Toggle. resetMouseDelta() ist atomar und absichtlich Main-Thread-fähig
     * — es gehört in jedem dieser Tasks VOR den eigentlichen Sprung.
     */
    public void disableCursor() {
        SkyEngine.get().addTaskToMainThread(() -> {
            /* VOR dem Moduswechsel: GLFW zentriert den Cursor dabei und feuert onCursorPos
               synchron — danach wäre der Sprung schon im rawMouse und der Render-Thread könnte
               ihn als echtes Delta auf den Blick anwenden. */
            this.resetMouseDelta();
            GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            if (GLFW.glfwRawMouseMotionSupported()) {
                GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
            }
            this.cursorGrabbed = true;
        });
    }

    public void showCursor() {
        this.showCursor(false);
    }

    /**
     * Gibt den Cursor frei. {@code center} setzt ihn im <b>selben</b> Main-Thread-Task in die
     * Fenstermitte — getrennte Tasks wären zwei Weckvorgänge, und dazwischen präsentiert der
     * Render-Thread Frames: der Zeiger würde erst an der von GLFW wiederhergestellten Position
     * auftauchen und dann sichtbar in die Mitte hüpfen.
     *
     * <p>Die Mitte kommt aus der Framebuffer-Größe, {@code glfwSetCursorPos} will
     * Content-Area-Koordinaten. Unter Windows mit DPI-Awareness identisch (und
     * {@code GLFW_SCALE_TO_MONITOR} wird nirgends gesetzt) — fiele das je auseinander, zielte die
     * "Mitte" daneben.
     */
    public void showCursor(boolean center) {
        SkyEngine.get().addTaskToMainThread(() -> {
            this.resetMouseDelta();   // vor dem Sprung, siehe disableCursor
            this.cursorGrabbed = false;
            /* Raw-Motion zuerst abschalten, dann Cursor freigeben. */
            if (GLFW.glfwRawMouseMotionSupported()) {
                GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE);
            }
            GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            if (center) {
                GLFW.glfwSetCursorPos(this.window.getWindowID(),
                        SkyEngine.get().getWindow().getWidth() / 2D,
                        SkyEngine.get().getWindow().getHeight() / 2D);
            }
        });
    }

    public void hideCursor() {
        GLFW.glfwSetInputMode(this.window.getWindowID(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
    }

    /*
     * Zwischenablage: glfwGet/SetClipboardString ist wie fast alles in GLFW Main-Thread-only,
     * aufgerufen wird es aber vom Render-Thread (Textfeld-Tasten). Deshalb dasselbe Deferral
     * wie beim Cursor. Beim Lesen brauchen wir das Ergebnis synchron — der Main-Thread hängt
     * in glfwWaitEvents() und wird von addTaskToMainThread geweckt, wartet also nie auf uns:
     * kein Deadlock. Kommt binnen 100 ms keine Antwort, liefern wir leer statt zu blockieren.
     */
    public String getClipboard() {
        if (this.window == null) return "";
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        SkyEngine.get().addTaskToMainThread(() -> {
            try {
                String value = GLFW.glfwGetClipboardString(this.window.getWindowID());
                result.complete(value != null ? value : "");
            } catch (Exception e) {
                result.complete("");
            }
        });
        try {
            return result.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            this.logger.error("Zwischenablage nicht lesbar (Timeout/Fehler)");
            return "";
        }
    }

    public void setClipboard(String text) {
        if (this.window == null) return;
        SkyEngine.get().addTaskToMainThread(
                () -> GLFW.glfwSetClipboardString(this.window.getWindowID(), text));
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
     * true, wenn der Cursor PHYSISCH gefangen ist (Spielmodus). Nicht dasselbe wie „kein GUI
     * offen": der Moduswechsel läuft deferiert auf dem Window-Thread, dazwischen liegen Frames, in
     * denen das GUI schon zu, der Cursor aber noch frei ist. Wer Maus-Delta auf den Blick anwendet,
     * muss zusätzlich hierauf prüfen — sonst dreht die freie Cursorbewegung die Kamera mit.
     */
    public boolean isCursorGrabbed() {
        return this.cursorGrabbed;
    }

    /**
     * Kündigt einen Cursor-Sprung an. Aufrufen, wann immer der Cursor "teleportiert":
     * Cursor-Modus-Wechsel, Fenstermodus-Wechsel, Zentrieren — und zwar <b>VOR</b> dem Sprung.
     * Verworfen wird das Delta dann bei der ersten Positionsmeldung danach, nicht schon jetzt
     * (Begründung an {@link #warpPending}).
     */
    public void resetMouseDelta() {
        this.warpPending = true;
    }
}
