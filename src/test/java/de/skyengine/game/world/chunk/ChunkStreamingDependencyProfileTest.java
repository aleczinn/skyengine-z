package de.skyengine.game.world.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChunkStreamingDependencyProfileTest {
    @Test
    void circularViewUsesExactStageSpecificChebyshevDependencyRings() {
        ChunkManager manager = new ChunkManager(null, null, 1, true);
        try {
            manager.configureStreamingDistance(16, 1);

            assertEquals(ChunkStatus.LIT, manager.streamingTargetStatus(16, 0));
            assertEquals(ChunkStatus.LIT, manager.streamingTargetStatus(17, 1),
                    "diagonal client mesh dependency at the disk boundary must be lit");
            assertEquals(ChunkStatus.DECORATED, manager.streamingTargetStatus(18, 2),
                    "the next ring is read by lighting but is never itself lit");
            assertEquals(ChunkStatus.GENERATED, manager.streamingTargetStatus(19, 3),
                    "the outer ring only supplies terrain to feature placement");
        } finally {
            manager.dispose();
        }
    }
}
