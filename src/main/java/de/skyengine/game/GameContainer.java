package de.skyengine.game;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.BlockRaycast;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.utils.Utils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

public class GameContainer implements IInitializable, IResizeable, IDisposable {

    private final Logger logger = LogManager.getLogger(GameContainer.class.getName());

    private Camera camera;
    private EntityPlayer player;
    private World world;

    private static final double REACH = 6.0;

    /* Wiederverwendet, um Allokationen pro Frame zu vermeiden */
    private final Vector3d rayDirection = new Vector3d();

    private boolean debugChunkBoundingBox = false;
    private boolean debugChunkWireframe = false;

    public GameContainer() {
        this.camera = new Camera();
        this.player = new EntityPlayer();
        this.player.setPosition(0, 90, 0);

        this.world = new World("world");
    }

    @Override
    public void init() {
        this.world.init(); // creates ChunkManager, renderer, texture array
        this.camera.setInverseDepth(SkyEngine.get().getWindow().getProperties().isUseInverseDepth());

        SkyEngine.get().getInput().centerMouse();
        SkyEngine.get().getInput().disableCursor();
    }

    public void update(Input input) {
        // TODO : Remove later
        if (input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            SkyEngine.get().shutdown();
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
        if (input.isKeyPressed(GLFW.GLFW_KEY_F11)) {
            this.logger.debug("Toggle Fullscreen");
        }

        this.player.update(input);
        this.world.update(input, this.player);
    }

    public void render(Input input, float partialTick) {

        /* Mouse look per frame */
        this.player.turn(input.getDeltaMouseX(), input.getDeltaMouseY());

        this.camera.follow(this.player, partialTick);
        this.camera.update(SkyEngine.get().getWindow().getAspectRatio());

        this.handleBlockInteraction(input);

        this.world.render(this.camera, partialTick);
    }

    @Override
    public void resize(int width, int height) {

    }

    @Override
    public void dispose() {
        if (this.world != null) {
            this.world.dispose();
        }
    }

    private void handleBlockInteraction(Input input) {
        boolean breakBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean placeBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        if (!breakBlock && !placeBlock) return;

        BlockRaycast.Hit hit = BlockRaycast.raycast(
                this.world,
                this.camera.getPosition(),
                this.camera.getDirection(this.rayDirection),
                REACH
        );
        if (hit == null) return;

        if (breakBlock) {
            this.world.setBlock(hit.x(), hit.y(), hit.z(), Blocks.AIR);
        } else {
            /* Platzieren: an der getroffenen Seite, nicht im Block selbst */
            int px = hit.x() + hit.faceX();
            int py = hit.y() + hit.faceY();
            int pz = hit.z() + hit.faceZ();

            /* Kamera-im-Block-Fall: face ist (0,0,0) -> würde den Zielblock ersetzen, abbrechen */
            if (hit.faceX() == 0 && hit.faceY() == 0 && hit.faceZ() == 0) return;

            if (this.world.getBlock(px, py, pz) == Blocks.AIR) {
                this.world.setBlock(px, py, pz, Blocks.STONE);
            }
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
