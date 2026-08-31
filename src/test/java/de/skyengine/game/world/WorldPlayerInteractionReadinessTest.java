package de.skyengine.game.world;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldPlayerInteractionReadinessTest {

    @Test
    void requiresReadyFullyUploadedVisibleL0Chunk() {
        assertFalse(Dimension.isPlayerInteractionReady(null));

        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.DECORATED;
        uploadAllSections(chunk);
        assertFalse(Dimension.isPlayerInteractionReady(chunk));

        chunk.status = ChunkStatus.READY;
        assertTrue(Dimension.isPlayerInteractionReady(chunk));
    }

    @Test
    void readyChunkRemainsLockedUntilEveryInitialSectionWasUploaded() {
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        for (int i = 0; i < Chunk.SECTIONS - 1; i++) chunk.markSectionUploaded(0, i);

        assertFalse(Dimension.isPlayerInteractionReady(chunk));

        chunk.markSectionUploaded(0, Chunk.SECTIONS - 1);
        assertTrue(Dimension.isPlayerInteractionReady(chunk));
    }

    private static void uploadAllSections(Chunk chunk) {
        for (int i = 0; i < Chunk.SECTIONS; i++) chunk.markSectionUploaded(0, i);
    }
}
