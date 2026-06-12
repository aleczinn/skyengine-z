package de.skyengine.game;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.World;
import de.skyengine.graphics.camera.Camera;
import org.lwjgl.glfw.GLFW;

public class GameContainer implements IInitializable, IResizeable, IDisposable {

    private Camera camera;
    private EntityPlayer player;
    private World world;

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
        SkyEngine.get().getInput().disableCursor();
    }

    public void update(Input input) {
        // TODO : Remove later
        if (input.isKeyDown(GLFW.GLFW_KEY_ESCAPE)) {
            SkyEngine.get().shutdown();
        }

        this.player.update(input);
        this.world.update(input, this.player);
    }

    public void render(Input input, float partialTick) {

        /* Mouse look per frame */
        this.player.turn(input.getDeltaMouseX(), input.getDeltaMouseY());

        this.camera.follow(this.player, partialTick);
        this.camera.update(SkyEngine.get().getWindow().getAspectRatio());

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
