package de.skyengine.core;

import de.skyengine.core.io.IDisposable;
import de.skyengine.graphics.framebuffer.FrameBuffer;
import de.skyengine.utils.DelayedRunnable;
import de.skyengine.utils.SpecsUtil;
import de.skyengine.utils.StringUtils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class Window implements IDisposable {

    private final Logger logger = LogManager.getLogger(Window.class.getName());

    private final EngineConfig config;
    private final EngineProperties properties;

    private Long windowID = MemoryUtil.NULL;

    /* Gemerkte Windowed-Geometrie, um sie beim Zurückwechseln wiederherzustellen */
    private int windowedX, windowedY, windowedWidth, windowedHeight;

    private GLFWErrorCallback errorCallback;
    private Callback debugCallback;

    private GLCapabilities capabilities;

    private final FrameBuffer frameBuffer;

    /* Zeitpunkt des letzten Resize-Events (Main-Thread schreibt im Callback, Render-Thread liest).
       Solange das jünger als RESIZE_PAUSE_NANOS ist, gilt das Fenster als "wird gerade resized". */
    private volatile long lastResizeNanos;
    private static final long RESIZE_PAUSE_NANOS = 150_000_000L;

    public Window(EngineConfig config) {
        this.config = config;
        this.properties = new EngineProperties();

        this.initGLFW();
        this.initWindowHints();
        this.createWindow();

        this.frameBuffer = new FrameBuffer(this.config, this.properties);
    }

    public void init() {
        GLFW.glfwMakeContextCurrent(this.windowID);
        this.capabilities = GL.createCapabilities();
        this.properties.update(this.capabilities);

        this.initDebugCallback();

        GLFW.glfwSetFramebufferSizeCallback(this.windowID, (window, width, height) -> {
            if (width <= 0 || height <= 0) return;

            this.config.setWindowWidth(width);
            this.config.setWindowHeight(height);
            this.lastResizeNanos = System.nanoTime();

            SkyEngine.get().getRenderTasks().add(new DelayedRunnable(() -> {
                this.frameBuffer.create();
                SkyEngine.get().onResize(width, height);
                GL11.glViewport(0, 0, width, height);
                return null;
            }, "Framebuffer size change", 0));
        });

        GLFW.glfwSetWindowIconifyCallback(this.windowID, (window, minimized) -> {
            this.config.setMinimized(minimized);
        });

        GLFW.glfwSetWindowMaximizeCallback(this.windowID, (window, maximized) -> {
            this.config.setMaximized(maximized);
        });

        GLFW.glfwSwapInterval(this.config.isVSync() ? 1 : 0);
    }

    /**
     * true, solange das Fenster gerade aktiv resized wird (letztes Resize-Event jünger als
     * {@link #RESIZE_PAUSE_NANOS}). Der Render-Thread pausiert dann Rendern/Swappen, um das
     * Desktop-Flackern durch ungebremstes SwapBuffers in der modalen Win32-Resize-Schleife zu
     * vermeiden. Wird vom Render-Thread gelesen (lock-frei über das volatile Feld).
     */
    public boolean isResizing() {
        return System.nanoTime() - this.lastResizeNanos < RESIZE_PAUSE_NANOS;
    }

    private void initGLFW() {
        if (this.errorCallback == null) {
            GLFW.glfwSetErrorCallback(this.errorCallback = GLFWErrorCallback.createPrint(System.err));
            GLFW.glfwInitHint(GLFW.GLFW_JOYSTICK_HAT_BUTTONS, GLFW.GLFW_FALSE);

            if (!GLFW.glfwInit()) throw new RuntimeException("SkyEngine - Unable to initialize GLFW!");
        }
    }

    private void initWindowHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);

        /* Disable window framebuffer bits, because we render into our separate FBO */
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 0);

        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GL11.GL_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, this.config.isResizeable() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, (this.config.isFullscreen() || this.config.isBorderlessFullscreen()) ? GLFW.GLFW_FALSE : (this.config.isMaximized() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE));
        GLFW.glfwWindowHint(GLFW.GLFW_DOUBLEBUFFER, GLFW.GLFW_TRUE);

        if (!this.config.getDebugMode().equals(EngineConfig.DebugMode.NONE)) {
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE);
        }
    }

    private void createWindow() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode vidMode  = GLFW.glfwGetVideoMode(monitor);

        this.windowID = GLFW.glfwCreateWindow(
                this.config.getWindowWidth(),
                this.config.getWindowHeight(),
                this.config.getTitle(),
                MemoryUtil.NULL,
                MemoryUtil.NULL
        );

        if (this.windowID == MemoryUtil.NULL) throw new RuntimeException("Failed to create the window!");

        GLFW.glfwSetWindowSizeLimits(
                this.windowID,
                this.config.getWindowMinWidth() > -1 ? this.config.getWindowMinWidth() : GLFW.GLFW_DONT_CARE,
                this.config.getWindowMinHeight() > -1 ? this.config.getWindowMinHeight() : GLFW.GLFW_DONT_CARE,
                this.config.getWindowMaxWidth() > -1 ? this.config.getWindowMaxWidth() : GLFW.GLFW_DONT_CARE,
                this.config.getWindowMaxHeight() > -1 ? this.config.getWindowMaxHeight() : GLFW.GLFW_DONT_CARE
        );

        this.applyWindowMode(this.config.getWindowMode(), vidMode);

        if (this.config.getWindowIconPaths() != null) {
            this.setIcon(this.config.getWindowIconPaths());
        }
    }

    /**
     * Wechselt den Fenstermodus zur Laufzeit.
     * NUR vom Main-Thread aufrufen (via SkyEngine.runOnMainThread)!
     */
    public void setWindowMode(EngineConfig.WindowMode mode) {
        if (mode == this.config.getWindowMode()) return;

        /* Beim Verlassen des Windowed-Modus die Geometrie merken */
        if (this.config.isWindowed()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1);
                IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
                GLFW.glfwGetWindowPos(this.windowID, x, y);
                GLFW.glfwGetWindowSize(this.windowID, w, h);
                this.windowedX = x.get(0);
                this.windowedY = y.get(0);
                this.windowedWidth = w.get(0);
                this.windowedHeight = h.get(0);
            }
        }

        this.config.setWindowMode(mode);
        this.applyWindowMode(mode, GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor()));

        /* Fenstermodus-Wechsel repositioniert den Cursor -> Delta verwerfen */
        SkyEngine.get().getInput().resetMouseDelta();
    }

    /**
     * Gemeinsamer Kern: wendet einen Modus auf das existierende Fenster an.
     * Wird beim Erstellen (Main-Thread) und beim Laufzeit-Wechsel (Main-Thread) genutzt.
     */
    private void applyWindowMode(EngineConfig.WindowMode mode, GLFWVidMode vidMode) {
        switch (mode) {
            case WINDOWED -> {
                GLFW.glfwSetWindowAttrib(this.windowID, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);

                /* Gemerkte Geometrie nutzen, sonst (beim Start) zentrieren */
                int w = this.windowedWidth > 0 ? this.windowedWidth : this.config.getWindowWidth();
                int h = this.windowedHeight > 0 ? this.windowedHeight : this.config.getWindowHeight();
                int x = this.windowedWidth > 0 ? this.windowedX : vidMode.width() / 2 - w / 2;
                int y = this.windowedHeight > 0 ? this.windowedY : vidMode.height() / 2 - h / 2;

                GLFW.glfwSetWindowMonitor(this.windowID, MemoryUtil.NULL, x, y, w, h, GLFW.GLFW_DONT_CARE);

                if (this.config.isMaximized()) {
                    GLFW.glfwMaximizeWindow(this.windowID);
                }
            }
            case FULLSCREEN -> GLFW.glfwSetWindowMonitor(
                    this.windowID, GLFW.glfwGetPrimaryMonitor(),
                    0, 0, vidMode.width(), vidMode.height(), vidMode.refreshRate());

            case BORDERLESS_FULLSCREEN -> {
                GLFW.glfwSetWindowAttrib(this.windowID, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowMonitor(this.windowID, MemoryUtil.NULL,
                        0, 0, vidMode.width(), vidMode.height(), GLFW.GLFW_DONT_CARE);
            }
        }
    }

    public void setIcon(String... paths) {
        GLFWImage.Buffer images = GLFWImage.malloc(paths.length);

        for (String path : paths) {
            File file = new File(path);

            if (!file.exists()) {
                this.logger.fatal(new RuntimeException("Icon: " + path + " could not be found!"));
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);

                ByteBuffer buffer = STBImage.stbi_load(path, w, h, comp, 4);

                if (buffer != null) {
                    GLFWImage image = GLFWImage.malloc();
                    image.set(w.get(0), h.get(0), buffer);
                    images.put(image);
                    image.free();
                }
            }
        }

        images.position(0);
        GLFW.glfwSetWindowIcon(this.windowID, images);
        images.free();
    }

    private void initDebugCallback() {
        if (!this.config.getDebugMode().equals(EngineConfig.DebugMode.NONE)) {
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE);

            this.debugCallback = GLUtil.setupDebugMessageCallback();

            GL43.glDebugMessageControl(
                    GL11.GL_DONT_CARE, GL11.GL_DONT_CARE,
                    GL43.GL_DEBUG_SEVERITY_NOTIFICATION,
                    (int[]) null, false
            );

            // NVIDIA-Performance-Warnung 0x20052 ("Pixel transfer is synchronized with 3D rendering")
            // stummschalten: wird durch die winzigen Sprite-Animations-Uploads (16x16, ~1 KB) ausgeloest
            // und ist in dieser Groessenordnung irrelevant.
            GL43.glDebugMessageControl(
                    GL43.GL_DEBUG_SOURCE_API, GL43.GL_DEBUG_TYPE_PERFORMANCE,
                    GL11.GL_DONT_CARE,
                    new int[] { 0x20052 }, false
            );

            if (this.properties.isUseSynchronousDebugCallback()) {
                GL11.glEnable(ARBDebugOutput.GL_DEBUG_OUTPUT_SYNCHRONOUS_ARB);
            }
        }
    }

    public void printDebug() {
        this.logger.info(StringUtils.padBoth("[ Engine ]", 50, '='));
        this.logger.info("Starting Engine");
        this.logger.info("Using LWJGL " + SpecsUtil.getLWJGLVersion());
        this.logger.info("OS: " + SpecsUtil.getOS());
        this.logger.info("OpenGL Vendor : " + GL11.glGetString(GL11.GL_VENDOR));
        this.logger.info("Driver Version: " + GL11.glGetString(GL11.GL_VERSION));
        this.logger.info("OpenGL Renderer : " + GL11.glGetString(GL11.GL_RENDERER));
        this.logger.info("GLSL Version: " + SpecsUtil.getGLSLVersion());
        this.logger.info("Java: " + SpecsUtil.getJava());
        this.logger.info("Max Texture Size: " + GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE));

        this.logger.debug(StringUtils.padBoth("[ GLCapabilities ]", 50, '='));
        this.logger.debug("UseDirectStateAccess: " + this.properties.isUseDirectStateAccess());
        this.logger.debug("UseMultiDrawIndirect: " + this.properties.isUseMultiDrawIndirect());
        this.logger.debug("UseBufferStorage: " + this.properties.isUseBufferStorage());
        this.logger.debug("UseClearBuffer: " + this.properties.isUseClearBuffer());
        this.logger.debug("UseInverseDepth: " + this.properties.isUseInverseDepth());
        this.logger.debug("CanUseSynchronousDebugCallback: " + this.properties.isUseSynchronousDebugCallback());
        this.logger.debug("GenerateDrawCallsViaShader: " + this.properties.isGenerateDrawCallsViaShader());
        this.logger.debug("UseOcclusionCulling: " + this.properties.isUseOcclusionCulling());
        this.logger.debug("UseRepresentativeFragmentTest: " + this.properties.isUseRepresentativeFragmentTest());
        this.logger.debug("UniformBufferOffsetAlignment: " + this.properties.getUniformBufferOffsetAlignment());
    }

    @Override
    public void dispose() {
        if (this.debugCallback != null) {
            this.debugCallback.free();
            this.debugCallback = null;
        }

        // Free the window callbacks and destroy the window
        Callbacks.glfwFreeCallbacks(this.windowID);
        GLFW.glfwDestroyWindow(this.windowID);

        // Terminate GLFW and free the error callback
        GLFW.glfwTerminate();
        this.errorCallback.free();
        this.errorCallback = null;
    }

    public boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(this.windowID);
    }

    public void forceClose() {
        GLFW.glfwSetWindowShouldClose(this.windowID, true);
    }

    public Long getWindowID() {
        return windowID;
    }

    public String getTitle() {
        return this.config.getTitle();
    }

    public void setTitle(String title) {
        this.config.setTitle(title);
        GLFW.glfwSetWindowTitle(this.windowID, title);
    }

    public int getWidth() {
        return this.config.getWindowWidth();
    }

    public int getHeight() {
        return this.config.getWindowHeight();
    }

    public double getAspectRatio() {
        return this.config.getAspectRatio();
    }

    public boolean isMaximized() {
        return this.config.isMaximized();
    }

    public boolean isMinimized() {
        return this.config.isMinimized();
    }

    public boolean isVSync() {
        return this.config.isVSync();
    }

    public void setVsync(boolean vsync) {
        this.config.setVSync(vsync);
        GLFW.glfwSwapInterval(vsync ? 1 : 0);
    }

    public GLCapabilities getCapabilities() {
        return capabilities;
    }

    public EngineProperties getProperties() {
        return properties;
    }

    public FrameBuffer getFrameBuffer() {
        return frameBuffer;
    }
}
