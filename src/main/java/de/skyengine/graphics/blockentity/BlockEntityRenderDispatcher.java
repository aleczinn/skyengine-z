package de.skyengine.graphics.blockentity;

import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.graphics.camera.Camera;

import java.util.HashMap;
import java.util.Map;

/**
 * Hält die {@link BlockEntityRenderer} je {@link BlockEntityType} und zeichnet pro Frame alle
 * BlockEntities geladener Chunks, die einen Renderer haben. Läuft NACH dem Chunk-Mesh.
 */
public final class BlockEntityRenderDispatcher {

    private final Map<BlockEntityType<?>, BlockEntityRenderer> renderers = new HashMap<>();

    public void register(BlockEntityType<?> type, BlockEntityRenderer renderer) {
        this.renderers.put(type, renderer);
    }

    public void init() {
        for (BlockEntityRenderer renderer : this.renderers.values()) renderer.init();
    }

    public void render(ChunkManager chunkManager, Camera camera, float partialTick) {
        if (this.renderers.isEmpty()) return;
        for (Chunk chunk : chunkManager.loadedChunks()) {
            if (chunk.status != ChunkStatus.READY) continue;
            for (BlockEntity be : chunk.blockEntities()) {
                BlockEntityRenderer renderer = this.renderers.get(be.getType());
                if (renderer != null) renderer.render(be, camera, partialTick);
            }
        }
    }

    public void dispose() {
        for (BlockEntityRenderer renderer : this.renderers.values()) renderer.dispose();
    }
}
