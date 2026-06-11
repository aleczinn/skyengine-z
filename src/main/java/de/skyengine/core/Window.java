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

    private GLFWErrorCallback errorCallback;
    private Callback debugCallback;

    private GLCapabilities capabilities;

    private final FrameBuffer frameBuffer;

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

        if (!(GLFW.glfwExtensionSupported("GL_EXT_framebuffer_object") || GLFW.glfwExtensionSupported("GL_ARB_framebuffer_object"))) {
            throw new RuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: " + GL11.glGetString(GL11.GL_VERSION) + ", FBO extension: false");
        }

        this.initDebugCallback();

        GLFW.glfwSetFramebufferSizeCallback(this.windowID, (window, width, height) -> {
            if (width <= 0 && height <= 0) return;

            this.config.setWindowWidth(width);
            this.config.setWindowHeight(height);

            SkyEngine.get().getTasks().add(new DelayedRunnable(() -> {
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
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);

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
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);

        this.windowID = GLFW.glfwCreateWindow(
                this.config.getWindowWidth(),
                this.config.getWindowHeight(),
                this.config.getTitle(),
                this.config.isFullscreen() ? monitor : MemoryUtil.NULL, MemoryUtil.NULL
        );

        if (this.windowID == MemoryUtil.NULL) throw new RuntimeException("Failed to create the window!");

        GLFW.glfwSetWindowSizeLimits(
                this.windowID,
                this.config.getWindowMinWidth() > -1 ? this.config.getWindowMinWidth() : GLFW.GLFW_DONT_CARE,
                this.config.getWindowMinHeight() > -1 ? this.config.getWindowMinHeight() : GLFW.GLFW_DONT_CARE,
                this.config.getWindowMaxWidth() > -1 ? this.config.getWindowMaxWidth() : GLFW.GLFW_DONT_CARE,
                this.config.getWindowMaxHeight() > -1 ? this.config.getWindowMaxHeight() : GLFW.GLFW_DONT_CARE
        );

        this.setupWindowMode(mode);

        if (this.config.getWindowIconPaths() != null) {
            this.setIcon(this.config.getWindowIconPaths());
        }
    }

    private void setupWindowMode(GLFWVidMode vidMode) {
        switch (this.config.getWindowMode()) {
            case WINDOWED:
                if (!this.config.isMaximized()) {
                    GLFW.glfwSetWindowPos(this.windowID, vidMode.width() / 2 - this.config.getWindowWidth() / 2, vidMode.height() / 2 - this.config.getWindowHeight() / 2);
                }
                break;
            case FULLSCREEN:
                GLFW.glfwSetWindowMonitor(this.windowID, GLFW.glfwGetPrimaryMonitor(), 0, 0, vidMode.width(), vidMode.height(), GLFW.GLFW_DONT_CARE);
                break;
            case BORDERLESS_FULLSCREEN:
                GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowMonitor(this.windowID, GLFW.glfwGetPrimaryMonitor(), 0, 0, vidMode.width(), vidMode.height(), GLFW.GLFW_DONT_CARE);
                break;
        }

        if (this.config.isWindowed() && this.config.isMaximized()) {
            this.config.setWindowWidth(vidMode.width());
            this.config.setWindowHeight(vidMode.height());
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
        this.logger.debug("UseNvMultisampleCoverage: " + this.properties.isUseNvMultisampleCoverage());
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
