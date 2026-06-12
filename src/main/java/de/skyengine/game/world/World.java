package de.skyengine.game.world;

import de.skyengine.core.input.Input;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.io.IInitializable;
import de.skyengine.core.io.IRenderable;
import de.skyengine.core.io.IUpdatable;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.graphics.camera.Camera;

public class World implements IInitializable, IDisposable {

    private final String name;

    public World(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public void init() {

    }

    public void update(Input input, EntityPlayer player) {

    }

    public void render(Camera camera, float partialTick) {

    }

    @Override
    public void dispose() {

    }
}
