package de.skyengine.game;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.Files;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockRaycast;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.ui.UIRenderer;
import de.skyengine.graphics.world.SelectionBoxRenderer;
import de.skyengine.utils.Utils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.io.File;

public class GameContainer implements IInitializable, IResizeable, IDisposable {

    private final Logger logger = LogManager.getLogger(GameContainer.class.getName());

    private Camera camera;
    private EntityPlayer player;
    private World world;
    private SelectionBoxRenderer selectionBoxRenderer;
    private UIRenderer uiRenderer;

    private static final double REACH = 6.0;

    /* Block-Interaktion: sofort beim Klick, beim Halten alle 200ms (= 4 Ticks, wie Minecraft) */
    private static final long INTERACT_DELAY_MS = 200;
    private long lastBreakTime = 0;
    private long lastPlaceTime = 0;

    /* Wiederverwendet, um Allokationen pro Frame zu vermeiden */
    private final Vector3d rayDirection = new Vector3d();

    private boolean debugChunkBoundingBox = false;
    private boolean debugChunkWireframe = false;

    private BlockRaycast.Hit hit = null;

    public GameContainer() {
        this.camera = new Camera();
        this.player = new EntityPlayer();
        this.player.setPosition(0, 90, 0);

        this.world = new World("world");
        this.selectionBoxRenderer = new SelectionBoxRenderer();
        this.uiRenderer = new UIRenderer();
    }

    @Override
    public void init() {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        this.world.init(); // creates ChunkManager, renderer, texture array
        this.camera.setInverseDepth(SkyEngine.get().getWindow().getProperties().isUseInverseDepth());
        this.selectionBoxRenderer.init();
        this.uiRenderer.init();

        SkyEngine.get().getInput().centerMouse();
        SkyEngine.get().getInput().disableCursor();
    }

    public void update(Input input) {
        this.player.update(input, this.world);
        this.world.update(input, this.player);
    }

    public void render(Input input, int width, int height, float partialTick) {
        this.handleDebugInput(input);

        /* Mouse look per frame */
        this.player.turn(input.getDeltaMouseX(), input.getDeltaMouseY());

        this.camera.follow(this.player, partialTick);
        this.camera.update((double) width / height);

        this.hit = BlockRaycast.raycast(
                this.world,
                this.camera.getPosition(),
                this.camera.getDirection(this.rayDirection),
                REACH
        );

        this.handleBlockInteraction(input);

        this.world.render(this.camera, partialTick);

        if (hit != null) {
            this.selectionBoxRenderer.render(this.camera, this.hit.x(), this.hit.y(), this.hit.z());
        }

        this.uiRenderer.render(width, height);
    }

    @Override
    public void resize(int width, int height) {

    }

    @Override
    public void dispose() {
        if (this.world != null) {
            this.world.dispose();
        }
        this.selectionBoxRenderer.dispose();
        this.uiRenderer.dispose();
    }

    private void handleBlockInteraction(Input input) {
        long now = System.currentTimeMillis();

        /* Sofort beim Klick (isMousePressed) ODER beim Halten nach Ablauf des Delays */
        boolean breakBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                || (input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) && now - this.lastBreakTime >= INTERACT_DELAY_MS);

        boolean placeBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                || (input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT) && now - this.lastPlaceTime >= INTERACT_DELAY_MS);

        if (!breakBlock && !placeBlock) return;
        if (hit == null) return;

        if (breakBlock) {
            this.world.setBlock(hit.x(), hit.y(), hit.z(), Blocks.AIR);
            this.lastBreakTime = now;
        } else {
            /* Platzieren: an der getroffenen Seite, nicht im Block selbst */
            int px = hit.x() + hit.faceX();
            int py = hit.y() + hit.faceY();
            int pz = hit.z() + hit.faceZ();

            /* Kamera-im-Block-Fall: face ist (0,0,0) -> würde den Zielblock ersetzen, abbrechen */
            if (hit.faceX() == 0 && hit.faceY() == 0 && hit.faceZ() == 0) return;

            if (this.world.getBlock(px, py, pz) == Blocks.AIR) {
                /* Nicht in den eigenen Körper bauen */
                AABB blockBox = new AABB(px, py, pz, px + 1, py + 1, pz + 1);
                if (!blockBox.intersects(this.player.getBoundingBox())) {
                    this.world.setBlock(px, py, pz, Blocks.COBBLESTONE);
                    this.lastPlaceTime = now;
                }
            }
        }
    }

    private void handleDebugInput(Input input) {
        if (input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            SkyEngine.get().shutdown();
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F)) {
            this.player.toggleFlying();
            this.logger.debug("Flying: " + this.player.isFlying());
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F6)) {
            this.debugChunkWireframe = !this.debugChunkWireframe;
            this.logger.debug("Wireframe: " + this.debugChunkWireframe);
            Utils.setWireframe(this.debugChunkWireframe);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F7)) {
            this.debugChunkBoundingBox = !this.debugChunkBoundingBox;
            this.logger.debug("Chunk Bounding Box: " + this.debugChunkBoundingBox);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F8)) {
            this.world.getChunkManager().getChunks().clear();
            this.logger.debug("reload chunks");
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F11)) {
            boolean fullscreen = SkyEngine.get().getConfig().isWindowed();

            SkyEngine.get().getMainThreadTasks().add(() -> {
                SkyEngine.get().getWindow().setWindowMode(fullscreen ? EngineConfig.WindowMode.BORDERLESS_FULLSCREEN : EngineConfig.WindowMode.WINDOWED);
            });

            this.logger.debug("Toggle Fullscreen");
        }
    }

    public Camera getCamera() {
        return camera;
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    public World getWorld() {
        return world;
    }
}