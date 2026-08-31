package de.skyengine.core;

import de.skyengine.core.file.Files;
import de.skyengine.core.input.Input;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.resource.Resources;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.Screenshot;
import de.skyengine.graphics.gui.BootProgress;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.utils.DelayedRunnable;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

import java.util.Iterator;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

public class SkyEngine {

    public static final String ENGINE_NAME = "SkyEngine";
    public static final String ENGINE_VERSION = "0.0.16-alpha";

    public static final String GAME_NAME = "Voxel Stories";
    public static final String GAME_PREFIX = derivePrefix(GAME_NAME);
    public static final String GAME_DATA_DIRECTORY_NAME = "." + GAME_PREFIX;

    private static String derivePrefix(String name) {
        String prefix = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replace("_", "");
        if (prefix.isEmpty()) throw new IllegalArgumentException("Spielname ergibt keinen gueltigen Prefix: " + name);
        return prefix;
    }

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
    private final PostProcessor postProcessor;

    /* Letzte 1-Sekunden-Zählwerte aus dem gameLoop — für On-GuiScreen-Anzeigen (F3). */
    private int currentFps;
    private int currentTps;

    public SkyEngine(EngineConfig config) {
        instance = this;

        this.config = config;
        this.window = new Window(config);
        this.input = new Input(this.window);
        this.files = new Files();
        this.mainThreadTasks = new ConcurrentLinkedQueue<>();
        this.renderTasks = new ConcurrentLinkedQueue<>();
        /* Packs muessen vor GameContainer/I18n/Fonts aktiv sein. */
        Resources.initialize();
        this.game = new GameContainer();
        this.postProcessor = new PostProcessor(); // GL-Init erst in launch() (Render-Thread)
    }

    private void onUpdate() {
        this.game.update(this.input);
    }

    private void onRender(float partialTick) {
        FrameProfiler.newFrame();
        FrameProfiler.cpuStart(FrameProfiler.Cpu.FRAME);

        FrameProfiler.cpuStart(FrameProfiler.Cpu.CLEAR);

        /* Der Szene-Framebuffer folgt dem AA-Modus (MSAA-Modus multisampelt, alle anderen
           liefern die Depth-Textur für TAA) — Neuaufbau nur bei Moduswechsel (F9/F10),
           und VOR bind/clear, damit kein halb-initialisierter Frame entsteht. */
        int wantedSamples = this.postProcessor.getSettings()
                .effectiveMsaaSamples(GameSettings.get().msaaSamples);
        if (wantedSamples != this.window.getFrameBuffer().getSamples()) {
            this.window.getFrameBuffer().create();
        }

        this.window.getFrameBuffer().bind();

        de.skyengine.game.world.Dimension activeWorld = this.game.getDimension();
        if (activeWorld != null) {
            var environment = activeWorld.getEnvironment();
            GL11.glClearColor(environment.backgroundRed(), environment.backgroundGreen(),
                    environment.backgroundBlue(), 1.0F);
        } else {
            GL11.glClearColor(
                    this.config.getWindowClearColor().red,
                    this.config.getWindowClearColor().green,
                    this.config.getWindowClearColor().blue,
                    this.config.getWindowClearColor().alpha
            );
        }

        /* Depth-Test/Cull-Face pro Frame neu aktivieren: GUI-/BlockEntity-Renderer deaktivieren
           sie ohne Restore. Die Basis-Depth-Func spiegelt EngineProperties.baseDepthFunc() —
           beide Stellen müssen konsistent bleiben (Reversed-Z: GREATER). Statisches State
           (Clip-Control, ClearDepth, Primitive-Restart, Blend-Func) wird einmalig in launch()
           gesetzt statt pro Frame. */
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        de.skyengine.graphics.GlState.forceCullFaceEnabled();
        GL11.glDepthFunc(this.window.getProperties().baseDepthFunc());

        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        FrameProfiler.cpuStop(FrameProfiler.Cpu.CLEAR);

        /* Welt in das Szene-Target (HDR); die GUI kommt erst NACH der Post-Kette in den
           Default-Framebuffer — HUD/Text durchlaufen nie Grading/AA (pixelgenau). */
        this.game.renderWorld(this.input, this.window.getWidth(), this.window.getHeight(), partialTick);

        FrameProfiler.gpuBegin(FrameProfiler.Gpu.RESOLVE);
        this.window.getFrameBuffer().resolve();
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.RESOLVE);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.POST);
        this.postProcessor.render(this.window.getFrameBuffer());
        FrameProfiler.cpuStop(FrameProfiler.Cpu.POST);

        this.game.renderDebugWorldOverlays();
        this.game.renderGui(this.window.getWidth(), this.window.getHeight());

        /* Screenshot aus dem fertigen Default-Framebuffer (inkl. GUI), vor dem Present. */
        if (this.game.consumeScreenshotRequest()) {
            this.game.notifyScreenshotResult(
                    Screenshot.capture(this.window.getWidth(), this.window.getHeight()));
        }

        /* End-Stempel der GPU-Frame-Spanne: letzter GL-Befehl vor dem Present */
        FrameProfiler.gpuFrameEnd();

        FrameProfiler.cpuStart(FrameProfiler.Cpu.SWAP);
        GLFW.glfwSwapBuffers(this.window.getWindowID());
        FrameProfiler.cpuStop(FrameProfiler.Cpu.SWAP);

        FrameProfiler.cpuStop(FrameProfiler.Cpu.FRAME);
    }

    public void onResize(int width, int height) {
        this.postProcessor.resize(width, height);
        this.game.resize(width, height);
    }

    private void onExit() {
        this.logger.info("Stopping!");

        this.game.dispose();
        this.postProcessor.dispose();
        FrameProfiler.dispose();

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
            FrameProfiler.cpuStart(FrameProfiler.Cpu.TICK);
            while (accumulatedTime >= TICK_TIME_NANOS && ticksProcessed < 10) {
                this.onUpdate();
                accumulatedTime -= TICK_TIME_NANOS;
                ticksProcessed++;
                updates++;
            }
            FrameProfiler.cpuStop(FrameProfiler.Cpu.TICK);

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
                /* Manche Render-Thread-Aktionen (z.B. Ressourcen-Reload) muessen warten, bis ihr
                   Zwischenbildschirm mindestens einmal praesentiert wurde. Direkt vor dem Frame
                   statt pro Tick: funktioniert auch im Hauptmenue und swapt nie waehrend Resize. */
                this.game.processDeferredGuiActions();
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

            /* Spike-Erfassung: einzelne lange Loop-Durchläufe (Tick+Frame) sofort mit ihren
               Sektionswerten loggen — die 1-s-Mittelwerte verstecken solche Ruckler komplett. */
            String spikeLine = FrameProfiler.loopEndSpikeLine();
            if (spikeLine != null) System.out.println(spikeLine);

            // show states each 1 second
            if (System.currentTimeMillis() - lastStatusTime >= 1000) {
                /* Letzte Sekundenwerte für On-GuiScreen-Anzeigen (F3-Debug-Overlay) festhalten. */
                this.currentFps = frames;
                this.currentTps = updates;
                System.out.printf("FPS: %d, TPS: %d%n", frames, updates);
                String profilerLine = FrameProfiler.statusLineAndReset();
                if (profilerLine != null) {
                    System.out.println(profilerLine);
                    String simulationLine = this.game.getDimension() != null
                            ? this.game.getDimension().getSimulationTelemetry().statusLineAndReset() : null;
                    if (simulationLine != null) System.out.println(simulationLine);
                    String syncLine = this.game.getDimension() != null
                            ? this.game.getDimensionView().chunks().syncStatsLineAndReset() : null;
                    if (syncLine != null) System.out.println(syncLine);
                }
                if (this.config.isWindowed() && !this.config.getDebugMode().equals(EngineConfig.DebugMode.NONE)) {
                    /* Ohne Welt (Hauptmenü) gibt es keine Chunk-/Spieler-Werte für den Titel. */
                    if (this.game.getDimension() != null && this.game.getPlayer() != null) {
                        this.window.setTitle("%s v%s | FPS: %d, TPS: %d | Sections: %d/%d | Chunks: %d | Player: X: %s Y: %s Z: %s".formatted(
                                GAME_NAME,
                                ENGINE_VERSION,
                                frames,
                                updates,
                                this.game.getDimensionView().chunks().getRenderedSections(),
                                this.game.getDimensionView().chunks().getTotalSections(),
                                this.game.getDimension().getChunkManager().getChunks().size(),
                                Math.round(this.game.getPlayer().x),
                                Math.round(this.game.getPlayer().y),
                                Math.round(this.game.getPlayer().z)
                        ));
                    } else {
                        this.window.setTitle("%s v%s | FPS: %d, TPS: %d".formatted(GAME_NAME, ENGINE_VERSION, frames, updates));
                    }
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

                /* Statisches GL-State einmalig (aus onRender hierher bewegt): verstellt niemand
                   dauerhaft — CrackRenderer stellt die Blend-Func nach seinem Draw wieder her,
                   Clip-Control/ClearDepth/Primitive-Restart fasst sonst kein Code an. */
                GL11.glEnable(GL31.GL_PRIMITIVE_RESTART);
                GL31.glPrimitiveRestartIndex(PRIMITIVE_RESTART_INDEX);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                if (this.window.getProperties().isUseInverseDepth()) {
                    /* Reversed-Z: Depth-Range [0,1] ohne Remapping + Clear auf "fern" = 0 */
                    ARBClipControl.glClipControl(GL20.GL_LOWER_LEFT, ARBClipControl.GL_ZERO_TO_ONE);
                    GL11.glClearDepth(0.0);
                } else {
                    GL11.glClearDepth(1.0);
                }

                // Configure VAOs, Shader, Textures here
                /* Post VOR dem Framebuffer: der FB liest den AA-Modus aus den
                   Post-Settings (MSAA-Modus => Multisample, sonst Depth-Textur). */
                this.postProcessor.init(this.window.getWidth(), this.window.getHeight());
                this.window.getFrameBuffer().create();

                /* Früher Boot-Anteil: nur Sprite-/Font-Renderer für den Ladebildschirm. */
                this.game.initBoot();

                GL11.glFlush();
                GL11.glFinish();

                /* Fenster JETZT zeigen (Main-Thread verlässt latch.await) — der Rest des
                   Boots läuft gestaffelt weiter und zeichnet Fortschritts-Frames dazwischen.
                   Der Main-Thread ist dabei schon im Event-Loop -> Fenster bleibt bedienbar. */
                latch.countDown();

                this.game.initStaged(new BootProgress(this.game.getGuiManager()));
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

    /** Reiht eine Aufgabe threadsicher für den nächsten Render-Thread-Durchlauf ein. */
    public void addTaskToRenderThread(Runnable task) {
        this.renderTasks.add(new DelayedRunnable(() -> {
            task.run();
            return null;
        }, "Render-Thread-Aufgabe", 0));
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

    /** FPS der letzten vollen Sekunde (0 bis zur ersten Messung). */
    public int getCurrentFps() {
        return currentFps;
    }

    /** TPS der letzten vollen Sekunde (0 bis zur ersten Messung). */
    public int getCurrentTps() {
        return currentTps;
    }

    public PostProcessor getPostProcessor() {
        return postProcessor;
    }

    public static SkyEngine get() {
        return instance;
    }
}
