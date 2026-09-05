package de.skyengine.game.world.chunk;

import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        this.manager.setReplicatedRenderAnchor(0, 0);

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

    @Test void loneReplicatedChunkWaitsForAnExactRealHalo() {
        this.manager = new ChunkManager(null, null, 1, true);
        this.renderGeneration = this.manager.attachRenderer();
        this.manager.setReplicatedRenderAnchor(0, 0);
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.LIT;

        this.manager.installReplicatedChunk(chunk);
        this.manager.awaitWorkerTasks();

        assertEquals(ChunkStatus.LIT, chunk.status);
        assertNull(this.manager.getUploadQueue().poll());
    }

    @Test void dependencyOnlyHaloFeedsOneVisibleMeshWithoutMeshingItself() {
        this.manager = new ChunkManager(null, null, 2, true);
        this.renderGeneration = this.manager.attachRenderer();
        this.manager.setReplicatedRenderAnchor(0, 0);

        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                Chunk dependency = new Chunk(x, z);
                dependency.status = ChunkStatus.LIT;
                this.manager.installReplicatedChunk(dependency, false);
            }
        }
        this.manager.awaitWorkerTasks();
        assertNull(this.manager.getUploadQueue().poll());

        this.manager.setReplicatedChunkVisible(0, 0, true);
        this.manager.awaitWorkerTasks();
        assertEquals(ChunkStatus.READY, this.manager.getChunk(0, 0).status);
        assertEquals(ChunkStatus.LIT, this.manager.getChunk(1, 0).status);
        assertEquals(1, this.manager.getUploadQueue().size(),
                "only the visible centre consumes a full mesh job");
    }

    @Test void missingHaloCornerPreventsMeshUntilItArrives() {
        this.manager = new ChunkManager(null, null, 1, true);
        this.renderGeneration = this.manager.attachRenderer();
        this.manager.setReplicatedRenderAnchor(0, 0);

        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                if (x == 1 && z == 1) continue;
                Chunk neighbour = new Chunk(x, z);
                neighbour.status = ChunkStatus.LIT;
                this.manager.installReplicatedChunk(neighbour);
            }
        }
        this.manager.awaitWorkerTasks();
        assertEquals(ChunkStatus.LIT, this.manager.getChunk(0, 0).status);
        assertNull(this.manager.getUploadQueue().poll());

        Chunk lastCorner = new Chunk(1, 1);
        lastCorner.status = ChunkStatus.LIT;
        this.manager.installReplicatedChunk(lastCorner);
        this.manager.awaitWorkerTasks();

        assertEquals(ChunkStatus.READY, this.manager.getChunk(0, 0).status);
        assertNotNull(this.manager.getUploadQueue().poll());
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

    @Test void everyEligibleCenterMeshesRegardlessOfSnapshotCompletionOrder() {
        this.manager = new ChunkManager(null, null, 2, true);
        this.renderGeneration = this.manager.attachRenderer();
        this.manager.setReplicatedRenderAnchor(0, 0);

        java.util.ArrayList<Chunk> incoming = new java.util.ArrayList<>();
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                Chunk chunk = new Chunk(x, z);
                chunk.status = ChunkStatus.LIT;
                incoming.add(chunk);
            }
        }
        java.util.Collections.shuffle(incoming, new java.util.Random(0x5A17E));
        incoming.forEach(this.manager::installReplicatedChunk);
        this.manager.awaitWorkerTasks();

        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                assertEquals(ChunkStatus.READY, this.manager.getChunk(x, z).status,
                        "eligible center remained dormant at " + x + "," + z);
            }
        }
        assertEquals(9, this.manager.getUploadQueue().size());
    }

    @Test void presentationFrontNeverExposesAResidentChunkBeyondANearHole() {
        this.manager = new ChunkManager(null, null, 1, true);
        this.renderGeneration = this.manager.attachRenderer();
        this.manager.setRenderDistance(4);
        this.manager.setReplicatedRenderAnchor(0, 0);

        Chunk center = new Chunk(0, 0);
        center.status = ChunkStatus.READY;
        this.manager.installReplicatedChunk(center);
        for (int section = 0; section < Chunk.SECTIONS; section++) {
            center.markSectionUploaded(this.renderGeneration, section);
        }
        this.manager.replicatedUploadsApplied();
        this.manager.refreshReplicatedPresentation();

        assertEquals(0, this.manager.replicatedPresentationRadius());
        assertTrue(this.manager.isChunkPresented(0, 0));
        assertEquals(false, this.manager.isChunkPresented(2, 0));
    }

    @Test void movingRenderAnchorNeverHidesAnAlreadyPresentedResidentChunk() {
        this.manager = new ChunkManager(null, null, 1, true);
        this.renderGeneration = this.manager.attachRenderer();
        this.manager.setRenderDistance(4);
        this.manager.setReplicatedRenderAnchor(0, 0);
        Chunk oldCenter = installUploadedChunk(0, 0);
        this.manager.replicatedUploadsApplied();
        this.manager.refreshReplicatedPresentation();
        assertTrue(this.manager.isChunkPresented(0, 0));

        this.manager.setReplicatedRenderAnchor(3, 0);
        this.manager.refreshReplicatedPresentation();

        assertTrue(this.manager.isChunkPresented(0, 0),
                "an anchor move must not collapse the previous visible world");
        assertEquals(false, this.manager.isChunkPresented(3, 0));
    }

    @Test void newAnchorBootstrapsASecondContiguousFrontAfterFastTravel() {
        this.manager = new ChunkManager(null, null, 1, true);
        this.renderGeneration = this.manager.attachRenderer();
        this.manager.setRenderDistance(8);
        this.manager.setReplicatedRenderAnchor(0, 0);
        installUploadedChunk(0, 0);
        this.manager.replicatedUploadsApplied();
        this.manager.refreshReplicatedPresentation();

        this.manager.setReplicatedRenderAnchor(6, 0);
        installUploadedChunk(6, 0);
        this.manager.replicatedUploadsApplied();
        this.manager.refreshReplicatedPresentation();

        assertTrue(this.manager.isChunkPresented(0, 0));
        assertTrue(this.manager.isChunkPresented(6, 0));
    }

    private Chunk installUploadedChunk(int x, int z) {
        Chunk chunk = new Chunk(x, z);
        chunk.status = ChunkStatus.READY;
        this.manager.installReplicatedChunk(chunk);
        for (int section = 0; section < Chunk.SECTIONS; section++) {
            chunk.markSectionUploaded(this.renderGeneration, section);
        }
        return chunk;
    }
}
