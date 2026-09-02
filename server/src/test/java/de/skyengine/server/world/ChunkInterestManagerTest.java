package de.skyengine.server.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkInterestManagerTest {
    @Test
    void movementDiffIsBoundedByViewAreaAndPrioritizesCenter() {
        ChunkInterestManager manager = new ChunkInterestManager();
        ChunkInterestManager.InterestDelta initial = manager.update("player", "skyengine:overworld",
                -10, 20, 2, 1, 0);
        assertEquals(13, initial.entered().size());
        assertTrue(initial.left().isEmpty());
        assertEquals(-10, initial.entered().getFirst().chunkX());
        assertEquals(20, initial.entered().getFirst().chunkZ());
        assertEquals(13, manager.trackedChunks("player"));

        ChunkInterestManager.InterestDelta unchanged = manager.update("player", "skyengine:overworld",
                -10, 20, 2, 1, 0);
        assertTrue(unchanged.entered().isEmpty());
        assertTrue(unchanged.left().isEmpty());

        ChunkInterestManager.InterestDelta moved = manager.update("player", "skyengine:overworld",
                -9, 20, 2, 1, 0);
        assertEquals(5, moved.entered().size());
        assertEquals(5, moved.left().size());
        assertEquals(-8, moved.entered().getFirst().chunkX());
        assertEquals(19, moved.entered().getFirst().chunkZ());
    }

    @Test
    void dimensionChangeUntracksOldDimensionWithoutWorldScan() {
        ChunkInterestManager manager = new ChunkInterestManager();
        manager.update("player", "skyengine:overworld", 0, 0, 1, 0, 0);
        ChunkInterestManager.InterestDelta changed = manager.update("player", "skyengine:nether",
                0, 0, 1, 0, 0);
        assertEquals(5, changed.entered().size());
        assertEquals(5, changed.left().size());
        assertEquals(5, manager.remove("player").size());
        assertEquals(0, manager.trackedChunks("player"));
    }

    @Test
    void viewDistanceSixteenMatchesTheWorldManagersCircularLoadShape() {
        ChunkInterestManager manager = new ChunkInterestManager();
        var initial = manager.update("player", "skyengine:overworld", 0, 0, 16, 0, 0);
        assertEquals(797, initial.entered().size());
        assertEquals(797, manager.trackedChunks("player"));
    }
}
