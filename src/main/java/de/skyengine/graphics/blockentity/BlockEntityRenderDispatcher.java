package de.skyengine.graphics.blockentity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.world.ChunkRenderer;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Hält die {@link BlockEntityRenderer} je {@link BlockEntityType} und zeichnet pro Frame alle
 * BlockEntities geladener Chunks, die einen Renderer haben. Läuft NACH dem Chunk-Mesh.
 */
public final class BlockEntityRenderDispatcher {

    /** Konservativer Rand fürs Frustum-Culling (deckt Deckel-Animation, schwebendes Buch etc. ab). */
    private static final float CULL_MARGIN = 1.0f;

    private final Map<BlockEntityType<?>, BlockEntityRenderer> renderers = new HashMap<>();

    public void register(BlockEntityType<?> type, BlockEntityRenderer renderer) {
        this.renderers.put(type, renderer);
    }

    /** Renderer für einen Typ (z.B. für Inventar-Icons), oder null. */
    public BlockEntityRenderer get(BlockEntityType<?> type) {
        return this.renderers.get(type);
    }

    public void init() {
        for (BlockEntityRenderer renderer : this.renderers.values()) renderer.init();
    }

    public void render(ChunkManager chunkManager, LodManager lodManager, Camera camera, float partialTick,
                       float ambientLight) {
        if (this.renderers.isEmpty()) return;
        Vector3d cam = camera.getPosition();
        FrustumIntersection frustum = camera.getFrustum();
        /* Nur Chunks, die überhaupt BlockEntities haben (Manager-Buchführung) — der frühere
           Scan über ALLE geladenen Chunks inkl. Sicht-Gate-Lookup war O(Chunks) pro Frame. */
        for (Chunk chunk : chunkManager.chunksWithBlockEntities()) {
            if (chunk.status != ChunkStatus.READY) continue;
            /* Sicht-Gate wie im ChunkRenderer-Cull: solange das LOD die Zelle noch zeigt, ist
               der Chunk unsichtbar — seine BlockEntities dürfen nicht über dem LOD schweben. */
            if (lodManager != null && lodManager.lodShowsCell(chunk.chunkX, chunk.chunkZ)) continue;
            for (BlockEntity be : chunk.blockEntities()) {
                BlockEntityRenderer renderer = this.renderers.get(be.getType());
                if (renderer == null) continue;

                /* Frustum-Culling (kamerarelativ wie EntityRenderer): Block belegt pos..pos+1,
                   Margin deckt über den Block hinausragende Teile (Deckel, Buch) ab. */
                BlockPos pos = be.getPos();
                float ox = (float) (pos.x() - cam.x);
                float oy = (float) (pos.y() - cam.y);
                float oz = (float) (pos.z() - cam.z);
                if (!frustum.testAab(ox - CULL_MARGIN, oy - CULL_MARGIN, oz - CULL_MARGIN,
                        ox + 1f + CULL_MARGIN, oy + 1f + CULL_MARGIN, oz + 1f + CULL_MARGIN)) continue;

                /* Licht der eigenen Zelle (Himmel + Block) — Chunk und BlockPos liegen hier
                   ohnehin vor, ein World-Lookup wäre überflüssig. */
                int lx = pos.x() & ChunkSection.MASK, lz = pos.z() & ChunkSection.MASK;
                float light = ChunkRenderer.lightFactor(chunk.light.get(lx, pos.y(), lz),
                        chunk.blockLight.get(lx, pos.y(), lz), ambientLight);
                renderer.render(be, camera, partialTick, light);
            }
        }
    }

    public void dispose() {
        for (BlockEntityRenderer renderer : this.renderers.values()) renderer.dispose();
    }
}
