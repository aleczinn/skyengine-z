package de.skyengine.core;

import de.skyengine.core.file.Files;
import de.skyengine.core.input.Input;
import de.skyengine.game.GameContainer;
import de.skyengine.utils.DelayedRunnable;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

public class SkyEngine {

    public static final String ENGINE_NAME = "SkyEngine";
    public static final String ENGINE_VERSION = "1.0.0";

    /** The index/token used in an index buffer for primitive restart. */
    public static final int PRIMITIVE_RESTART_INDEX = 0xFFFF;

    private static SkyEngine instance = null;
    private final Logger logger = LogManager.getLogger(SkyEngine.class.getName());

    private static final int TPS = 20;
    private static final long TICK_TIME_NANOS = 1_000_000_000 / TPS;

    private final EngineConfig config;

    private final Window window;
    private final Input input;
    private final Files files;

    private final Queue<Runnable> mainThreadTasks;
    private final Queue<DelayedRunnable> renderTasks;

    private final GameContainer game;

    public SkyEngine(EngineConfig config) {
        instance = this;

        this.config = config;
        this.window = new Window(config);
        this.input = new Input(this.window);
        this.files = new Files();
        this.mainThreadTasks = new ConcurrentLinkedQueue<>();
        this.renderTasks = new ConcurrentLinkedQueue<>();
        this.game = new GameContainer();
    }

    private void onUpdate() {
        this.game.update(this.input);
    }

    private void onRender(float partialTick) {
        this.window.getFrameBuffer().bind();

        GL11.glClearColor(
                this.config.getWindowClearColor().red,
                this.config.getWindowClearColor().green,
                this.config.getWindowClearColor().blue,
                this.config.getWindowClearColor().alpha
        );

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL31.GL_PRIMITIVE_RESTART);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL31.glPrimitiveRestartIndex(PRIMITIVE_RESTART_INDEX);

        if (this.window.getProperties().isUseInverseDepth()) {
            ARBClipControl.glClipControl(GL20.GL_LOWER_LEFT, ARBClipControl.GL_ZERO_TO_ONE);
            GL11.glDepthFunc(GL11.GL_GREATER);
            GL11.glClearDepth(0.0);
        } else {
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glClearDepth(1.0);
        }

        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        this.game.render(this.input, this.window.getWidth(), this.window.getHeight(), partialTick);
        this.window.getFrameBuffer().blitToScreen();

        GLFW.glfwSwapBuffers(this.window.getWindowID());
    }

    public void onResize(int width, int height) {
        this.game.resize(width, height);
    }

    private void onExit() {
        this.logger.info("Stopping!");

        this.game.dispose();

        this.drainRunnables();
        GL.setCapabilities(null);
    }

    private void gameLoop() {
        this.drainRunnables();

        long lastTickTime = System.nanoTime();
        long accumulatedTime = 0;
        double lastLoopTime = 0;

        int frames = 0;
        int updates = 0;
        long lastStatusTime = System.currentTimeMillis();

        while (!this.window.shouldClose()) {
            long currentTime = System.nanoTime();
            long frameTime = currentTime - lastTickTime;
            lastTickTime = currentTime;
            lastLoopTime = GLFW.glfwGetTime();

            accumulatedTime += frameTime;

            this.drainRunnables();

            this.input.update();

            int ticksProcessed = 0;
            while (accumulatedTime >= TICK_TIME_NANOS && ticksProcessed < 10) {
                this.onUpdate();
                accumulatedTime -= TICK_TIME_NANOS;
                ticksProcessed++;
                updates++;
            }

            if (ticksProcessed >= 10) {
                this.logger.warning("Can't keep up with TPS! Skipping " + (accumulatedTime / TICK_TIME_NANOS) + " Ticks.");
                accumulatedTime = 0;
            }

            if (this.window.isResizing()) {
                /* Während eines aktiven Fenster-Resizes NICHT rendern/swappen. Ein zweiter Thread,
                   der während der modalen Win32-Resize-Schleife ungebremst swapt, lässt den ganzen
                   Desktop flackern. Ticks/Input laufen weiter, nur die Präsentation pausiert -
                   beim Loslassen wird normal weitergezeichnet (vgl. Minecraft). */
                try {
                    Thread.sleep(8);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } else {
                float partialTick = (float) accumulatedTime / TICK_TIME_NANOS;
                this.onRender(partialTick);
                frames++;

                /* Hintergrund-FPS-Drosselung: minimiertes Fenster auf backgroundFPS begrenzen,
                   um CPU/GPU/Strom zu sparen. backgroundFPS <= 0 = unbegrenzt. VSync paced
                   bereits selbst -> dann nicht zusätzlich syncen. Quelle ist das Window-Objekt;
                   Settings-Änderungen werden über GameContainer.applySettings dorthin gespiegelt. */
                int backgroundFPS = this.config.getBackgroundFPS();
                if (backgroundFPS > 0 && this.window.isMinimized() && !this.window.isVSync()) {
                    this.sync(backgroundFPS, lastLoopTime);
                }
            }

            // show states each 1 second
            if (System.currentTimeMillis() - lastStatusTime >= 1000) {
                System.out.printf("FPS: %d, TPS: %d%n", frames, updates);
                if (this.config.isWindowed() && !this.config.getDebugMode().equals(EngineConfig.DebugMode.NONE)) {
                    this.window.setTitle("%s v%s | FPS: %d, TPS: %d | Sections: %d/%d | Chunks: %d".formatted(
                            ENGINE_NAME,
                            ENGINE_VERSION,
                            frames,
                            updates,
                            this.game.getWorld().getChunkRenderer().getRenderedSections(),
                            this.game.getWorld().getChunkRenderer().getTotalSections(),
                            this.game.getWorld().getChunkManager().getChunks().size()
                    ));
                }

                frames = 0;
                updates = 0;
                lastStatusTime = System.currentTimeMillis();
            }
        }

        this.onExit();
    }

    public void sync(int fps, double currentLoopTime) {
        float targetTime = 1F / fps;
        double nextFrameTime = currentLoopTime + targetTime;

        while (GLFW.glfwGetTime() < nextFrameTime) {
            double remainingTime = nextFrameTime - GLFW.glfwGetTime();
            if (remainingTime > 0.002) { // Mehr als 2ms übrig -> sleep
                try {
                    Thread.sleep((long) (remainingTime * 900));
                } catch (InterruptedException e) {
                    this.logger.error(null, e);
                }
            } else {
                Thread.yield();
            }
        }
    }

    public void launch() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            this.renderTasks.add(new DelayedRunnable(() -> {
                this.window.init();
                this.window.printDebug();
                this.input.init();

                // Configure VAOs, Shader, Textures here
                this.window.getFrameBuffer().create();

                this.game.init();

                /* Make sure everything is ready before we show the window */
                GL11.glFlush();
                GL11.glFinish();

                /* Notify the latch so that the window can be shown */
                latch.countDown();
                return null;
            }, "Init", 0));

            /* Run logic updates and rendering in a separate thread */
            Thread updateAndRenderThread = new Thread(this::gameLoop);
            updateAndRenderThread.setName("Render Thread");
            updateAndRenderThread.setPriority(Thread.MAX_PRIORITY);
            updateAndRenderThread.start();

            /* Process OS/window event messages in this main thread */
            Thread.currentThread().setName("Window-Processing Thread");
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

            /* Wait for the latch to signal that init render thread actions are done */
            latch.await();
            this.runWindowProcessLoop();

            /*
             * After the runWindowProcessLoop exited (because the window was closed),
             * wait for render thread to complete finalization.
             */
            updateAndRenderThread.join();

            this.window.dispose();
        } catch (InterruptedException e) {
            this.logger.fatal(e);
        }
    }

    /**
     * Loop in the main thread to only process OS/window event messages.
     */
    private void runWindowProcessLoop() {
        GLFW.glfwShowWindow(this.window.getWindowID());

        while (!this.window.shouldClose()) {
            GLFW.glfwWaitEvents();

            Iterator<Runnable> iterator = this.mainThreadTasks.iterator();
            while (iterator.hasNext()) {
                Runnable dr = iterator.next();
                try {
                    iterator.remove();
                    dr.run();
                } catch (Exception e) {
                    this.logger.fatal(e);
                }
            }
        }
    }

    private void drainRunnables() {
        Iterator<DelayedRunnable> iterator = this.renderTasks.iterator();
        while (iterator.hasNext()) {
            DelayedRunnable dr = iterator.next();

            /* Check if we want to delay this runnable */
            if (dr.getDelay() > 0) {
                if (SkyEngine.get().getConfig().getDebugMode().equals(EngineConfig.DebugMode.FULL)) {
                    this.logger.debug("Delaying runnable [" + dr.getName() + "] for " + dr.getDelay() + " frames");
                }
                dr.reduceDelay();
                continue;
            }

            try {
                /* Remove from queue and execute */
                iterator.remove();
                dr.getRunnable().call();
            } catch (Exception e) {
                this.logger.fatal(e);
            }
        }
    }

    public void addTaskToMainThread(Runnable task) {
        this.mainThreadTasks.add(task);
        GLFW.glfwPostEmptyEvent();
    }

    /**
     * This method closes the game
     */
    public void shutdown() {
        this.window.forceClose();
    }

    public EngineConfig getConfig() {
        return config;
    }

    public Window getWindow() {
        return window;
    }

    public Input getInput() {
        return input;
    }

    public Files getFiles() {
        return files;
    }

    public Queue<Runnable> getMainThreadTasks() {
        return mainThreadTasks;
    }

    public Queue<DelayedRunnable> getRenderTasks() {
        return renderTasks;
    }

    public GameContainer getGame() {
        return game;
    }

    public static SkyEngine get() {
        return instance;
    }
}
