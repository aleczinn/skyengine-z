package de.skyengine.game.world.lod;

import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodManagerNeighborSnapshotTest {

    @Test
    void unchangedOwnLevelStillRemeshesWhenEastNeighborChangesFromL2ToL3() {
        LongIntMap desired = new LongIntMap(8);
        desired.put(LodManager.key(0, 0), 2);
        desired.put(LodManager.key(1, 0), 2);
        LodManager.LodNeighborSnapshot before =
                LodManager.neighborSnapshot(desired, 0, 0, 2);

        desired.put(LodManager.key(1, 0), 3);
        LodManager.LodNeighborSnapshot after =
                LodManager.neighborSnapshot(desired, 0, 0, 2);

        assertTrue(LodManager.topologyChanged(before, after));
        assertTrue(before.eastLevel() == 2);
        assertTrue(after.eastLevel() == 3);
        assertTrue(after.northLevel() == 2 && after.southLevel() == 2
                && after.westLevel() == 2,
                "Fehlende Nachbarn am aeusseren Ring bleiben regulaer");
    }

    @Test
    void identicalNeighborContractDoesNotTriggerARebuild() {
        LodManager.LodNeighborSnapshot snapshot =
                new LodManager.LodNeighborSnapshot(1, 2, 2, 3);
        assertFalse(LodManager.topologyChanged(snapshot, snapshot));
    }

    @Test
    void completedMeshWithStaleNeighborContractIsRejected() {
        LodManager.LodClipSnapshot clip = LodManager.LodClipSnapshot.centerOnly(0);
        LodManager.LodNeighborSnapshot built =
                new LodManager.LodNeighborSnapshot(2, 2, 2, 2);
        LodManager.LodNeighborSnapshot current =
                new LodManager.LodNeighborSnapshot(2, 2, 2, 3);

        assertFalse(LodManager.meshContractMatches(clip, built, clip, current));
        assertTrue(LodManager.meshContractMatches(clip, current, clip, current));
    }

    @Test
    void completedMeshWithStaleL0BoundaryOwnershipIsRejected() {
        LodManager.LodClipSnapshot built =
                new LodManager.LodClipSnapshot(1, 0, 0, 0, 0, 41L);
        LodManager.LodClipSnapshot current =
                new LodManager.LodClipSnapshot(1, 0, 0, 0, 0, 42L);
        LodManager.LodNeighborSnapshot neighbors =
                new LodManager.LodNeighborSnapshot(1, 1, 1, 1);

        assertFalse(LodManager.meshContractMatches(built, neighbors, current, neighbors));
        assertTrue(LodManager.meshContractMatches(current, neighbors, current, neighbors));
    }
}
