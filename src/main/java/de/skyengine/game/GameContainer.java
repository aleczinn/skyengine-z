package de.skyengine.game;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.World;
import de.skyengine.graphics.camera.Camera;

public class GameContainer implements IInitializable, IUpdatable, IRenderable, IResizeable, IDisposable {

    private Camera camera;
    private EntityPlayer player;
    private World world;

    public GameContainer() {
        this.camera = new Camera();
        this.player = new EntityPlayer();
        this.player.setPosition(0, 90, 0);

        this.world = new World("world");
        this.world.init(); // creates ChunkManager, renderer, texture array

        SkyEngine.get().getInput().disableCursor();
    }

    @Override
    public void init() {

    }

    @Override
    public void update(Input input) {
        this.player.update(input);
        this.world.update(input, this.player);
    }

    @Override
    public void render(float partialTick) {
        Input input = SkyEngine.get().getInput();

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
}
