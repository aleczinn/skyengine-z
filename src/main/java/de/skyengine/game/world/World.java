package de.skyengine.game.world;

import de.skyengine.core.input.Input;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.io.IInitializable;
import de.skyengine.core.io.IRenderable;
import de.skyengine.core.io.IUpdatable;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.world.ChunkRenderer;

public class World implements IInitializable, IDisposable {

    private final String name;

    private final WorldGenerator generator;
    private final ChunkManager chunkManager;
    private final ChunkRenderer chunkRenderer;

    public World(String name) {
        this.name = name;
        this.generator = new WorldGenerator(123);
        this.chunkManager = new ChunkManager(this.generator);
        this.chunkRenderer = new ChunkRenderer(this.chunkManager);
    }

    public String getName() {
        return name;
    }

    @Override
    public void init() {
        this.chunkRenderer.init();
    }

    public void update(Input input, EntityPlayer player) {
        this.chunkManager.update(player);
    }

    public void render(Camera camera, float partialTick) {
        this.chunkRenderer.render(camera);
    }

    @Override
    public void dispose() {

    }
}
