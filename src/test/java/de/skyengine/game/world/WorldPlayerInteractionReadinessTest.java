package de.skyengine.game.world;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldPlayerInteractionReadinessTest {

    @Test
    void requiresReadyFullyUploadedVisibleL0Chunk() {
        assertFalse(World.isPlayerInteractionReady(null, false));

        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.DECORATED;
        uploadAllSections(chunk);
        assertFalse(World.isPlayerInteractionReady(chunk, false));

        chunk.status = ChunkStatus.READY;
        assertTrue(World.isPlayerInteractionReady(chunk, false));
        assertFalse(World.isPlayerInteractionReady(chunk, true));

        chunk.pendingUnload = true;
        assertFalse(World.isPlayerInteractionReady(chunk, false));
    }

    @Test
    void readyChunkRemainsLockedUntilEveryInitialSectionWasUploaded() {
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        for (int i = 0; i < Chunk.SECTIONS - 1; i++) chunk.markSectionUploaded();

        assertFalse(World.isPlayerInteractionReady(chunk, false));

        chunk.markSectionUploaded();
        assertTrue(World.isPlayerInteractionReady(chunk, false));
    }

    private static void uploadAllSections(Chunk chunk) {
        for (int i = 0; i < Chunk.SECTIONS; i++) chunk.markSectionUploaded();
    }
}
