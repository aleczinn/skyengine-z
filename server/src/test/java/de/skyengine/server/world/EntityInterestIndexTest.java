package de.skyengine.server.world;

import de.skyengine.shared.entity.NetworkEntitySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityInterestIndexTest {
    @Test
    void entityMovementProducesBucketLocalEnterAndLeaveDiffs() {
        EntityInterestIndex index = new EntityInterestIndex();
        index.upsert(entity(1, 1, 1, 0));
        index.upsert(entity(2, 1000, 1000, 0));
        var initial = index.updateInterest("player", "skyengine:overworld", 0, 0, 2);
        assertEquals(1, initial.entered().size());
        assertEquals(1, initial.entered().getFirst().networkId());
        assertTrue(initial.left().isEmpty());

        var tracking = index.upsert(entity(1, 1000, 1000, 1));
        assertEquals(java.util.List.of("player"), tracking.leftSessions());
        var moved = index.updateInterest("player", "skyengine:overworld", 0, 0, 2);
        assertTrue(moved.entered().isEmpty());
        assertTrue(moved.left().isEmpty());
        assertTrue(index.trackingSessions(1).isEmpty());
    }

    private static NetworkEntitySnapshot entity(int id, double x, double z, long revision) {
        return new NetworkEntitySnapshot(id, 1, "skyengine:overworld", revision,
                x, 64, z, 0, 0, 0, 0, 0, null);
    }
}
