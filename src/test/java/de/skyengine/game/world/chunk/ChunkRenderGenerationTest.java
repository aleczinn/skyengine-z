package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.EntityPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkRenderGenerationTest {

    @Test
    void uploadResidencyIsUniquePerSectionAndGeneration() {
        Chunk chunk = new Chunk(0, 0);
        chunk.beginRenderGeneration(1);

        for (int section = 0; section < Chunk.SECTIONS - 1; section++) {
            chunk.markSectionUploaded(1, section);
        }
        for (int duplicate = 0; duplicate < 20; duplicate++) {
            chunk.markSectionUploaded(1, 0);
        }
        assertFalse(chunk.isFullyUploaded(), "duplicate uploads must not complete a chunk");

        chunk.markSectionUploaded(1, Chunk.SECTIONS - 1);
        assertTrue(chunk.isFullyUploaded());

        chunk.beginRenderGeneration(2);
        assertFalse(chunk.isFullyUploaded(), "a new GPU view starts without resident sections");
        chunk.markSectionUploaded(1, 0);
        assertFalse(chunk.isFullyUploaded(), "an upload from the destroyed view must be ignored");
    }

    @Test
    void managerReattachesExistingChunksWithoutDiscardingThem() {
        ChunkManager manager = new ChunkManager(null, null, 1);
        try {
            long first = manager.attachRenderer();
            Chunk chunk = new Chunk(4, -2);
            chunk.beginRenderGeneration(first);
            chunk.status = ChunkStatus.READY;
            manager.getChunks().put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
            for (int section = 0; section < Chunk.SECTIONS; section++) {
                chunk.markSectionUploaded(first, section);
            }
            assertTrue(chunk.isFullyUploaded());

            manager.detachRenderer(first);
            assertFalse(chunk.isFullyUploaded());
            assertTrue(chunk.status == ChunkStatus.LIT,
                    "a retained chunk must be remeshed instead of regenerated");

            long second = manager.attachRenderer();
            assertTrue(second != first);
            assertTrue(manager.getChunk(4, -2) == chunk, "terrain data must stay resident");
            assertFalse(chunk.isFullyUploaded());
            assertFalse(chunk.tryApplyMeshSection(first, 0, 99L, null),
                    "stale mesh results must not enter the new view");
            manager.detachRenderer(second);
        } finally {
            manager.dispose();
        }
    }

    @Test
    void minimumRenderDistanceDoesNotWaitForUnmeshableCornerChunks() {
        ChunkManager manager = new ChunkManager(null, null, 1);
        try {
            manager.setRenderDistance(2);
            long generation = manager.attachRenderer();
            Chunk center = new Chunk(0, 0);
            center.beginRenderGeneration(generation);
            center.status = ChunkStatus.READY;
            manager.getChunks().put(Chunk.key(0, 0), center);
            for (int section = 0; section < Chunk.SECTIONS; section++) {
                center.markSectionUploaded(generation, section);
            }

            EntityPlayer player = new EntityPlayer();
            player.setPosition(0.5, 80, 0.5);
            assertEquals(1F, manager.initialRenderProgress(player));
            manager.detachRenderer(generation);
        } finally {
            manager.dispose();
        }
    }
}
