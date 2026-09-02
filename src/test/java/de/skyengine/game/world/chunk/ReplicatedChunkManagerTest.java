package de.skyengine.game.world.chunk;

import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ReplicatedChunkManagerTest {
    private ChunkManager manager;
    private long renderGeneration;

    @BeforeAll static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @AfterEach void closeManager() {
        if (this.manager == null) return;
        this.manager.awaitWorkerTasks();
        this.manager.detachRenderer(this.renderGeneration);
        this.manager.dispose();
    }

    @Test void completeReplicatedNeighbourhoodUsesExistingFullColumnMesher() {
        this.manager = new ChunkManager(null, null, 1, true);
        this.renderGeneration = this.manager.attachRenderer();

        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                Chunk chunk = new Chunk(x, z);
                chunk.status = ChunkStatus.LIT;
                this.manager.installReplicatedChunk(chunk);
            }
        }
        this.manager.awaitWorkerTasks();

        Chunk center = this.manager.getChunk(0, 0);
        assertNotNull(center);
        assertEquals(ChunkStatus.READY, center.status);
        ChunkManager.MeshBatch batch = this.manager.getUploadQueue().poll();
        assertNotNull(batch);
        assertEquals(Chunk.SECTIONS, batch.results().size());
        assertEquals(this.renderGeneration, batch.renderGeneration());
    }

    @Test void replicatedUnloadInvalidatesRendererCleanupVersion() {
        this.manager = new ChunkManager(null, null, 1, true);
        this.renderGeneration = this.manager.attachRenderer();
        Chunk chunk = new Chunk(4, -7);
        chunk.status = ChunkStatus.LIT;
        this.manager.installReplicatedChunk(chunk);

        int before = this.manager.getChunkRemovalVersion();
        this.manager.removeReplicatedChunk(4, -7);

        assertEquals(null, this.manager.getChunk(4, -7));
        assertEquals(before + 1, this.manager.getChunkRemovalVersion());
    }
}
