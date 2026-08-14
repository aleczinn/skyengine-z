package de.skyengine.game.world.chunk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkManagerLodSchedulingTest {

    @Test
    void normalLodUsesBoundedWorkerWavesWhenForegroundQueueIsEmpty() {
        assertEquals(48, ChunkManager.normalLodSubmissionBudget(12, 0, 0));
        assertEquals(31, ChunkManager.normalLodSubmissionBudget(12, 0, 17));
        assertEquals(0, ChunkManager.normalLodSubmissionBudget(12, 0, 48));
    }

    @Test
    void queuedForegroundWorkStopsNewNormalLodSubmits() {
        assertEquals(0, ChunkManager.normalLodSubmissionBudget(12, 1, 0));
        assertEquals(0, ChunkManager.normalLodSubmissionBudget(12, 20, 3));
    }

    @Test
    void deferredRemeshMarkerDoesNotStarveNormalLod() throws Exception {
        ChunkManager manager = new ChunkManager(null, null);
        try {
            Field field = ChunkManager.class.getDeclaredField("remeshQueue");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<Chunk> markers = (ConcurrentLinkedQueue<Chunk>) field.get(manager);
            markers.add(new Chunk(0, 0));

            assertTrue(manager.normalLodSubmissionBudget() > 0,
                    "Ein zurückgestellter Remesh-Marker darf normale LOD-Jobs nicht sperren");
        } finally {
            manager.dispose();
        }
    }
}
