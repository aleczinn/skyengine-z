package de.skyengine.graphics.world;

import de.skyengine.game.world.lod.LodVolumeHierarchy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VolumetricLodRendererTest {

    @Test
    void descendantsMapToTheirRootAlsoAcrossNegativeCoordinates() {
        LodVolumeHierarchy.Key root = new LodVolumeHierarchy.Key(-2, 0, 3, 4);
        assertTrue(VolumetricLodRenderer.belongsToRoot(
                new LodVolumeHierarchy.Key(-31, 7, 63, 0), root));
        assertFalse(VolumetricLodRenderer.belongsToRoot(
                new LodVolumeHierarchy.Key(-33, 7, 63, 0), root));
    }

    @Test
    void activeTransitionMeshIsNotEvictedFromAnOldWorldFrame() {
        assertFalse(VolumetricLodRenderer.shouldEvict(50_000, 50_000));
        assertFalse(VolumetricLodRenderer.shouldEvict(50_600, 50_000));
        assertTrue(VolumetricLodRenderer.shouldEvict(50_601, 50_000));
    }

    @Test
    void splitDecisionHasAStableThirtyPercentHysteresisBand() {
        assertFalse(VolumetricLodRenderer.shouldSplit(false, true, 3, 114.9, 100.0));
        assertTrue(VolumetricLodRenderer.shouldSplit(false, true, 3, 115.1, 100.0));
        assertTrue(VolumetricLodRenderer.shouldSplit(true, true, 3, 85.1, 100.0));
        assertFalse(VolumetricLodRenderer.shouldSplit(true, true, 3, 84.9, 100.0));
    }

    @Test
    void emptyAndLevelZeroNodesNeverSplit() {
        assertFalse(VolumetricLodRenderer.shouldSplit(false, false, 3, 1_000.0, 100.0));
        assertFalse(VolumetricLodRenderer.shouldSplit(false, true, 0, 1_000.0, 100.0));
    }

    @Test
    void fixedDistanceBandsDoNotLetImportanceExpandL0() {
        int chunks = 16;
        assertTrue(VolumetricLodRenderer.shouldSplitForDistance(false, true, 2,
                900.0, chunks));
        assertFalse(VolumetricLodRenderer.shouldSplitForDistance(false, true, 2,
                1_100.0, chunks));
        assertFalse(VolumetricLodRenderer.shouldSplitForDistance(false, false, 4,
                0.0, chunks));
    }

    @Test
    void distanceBandHysteresisPreventsBoundaryOscillation() {
        int renderChunks = 16;
        double l2Boundary = renderChunks * 32.0 * 2.0;
        assertFalse(VolumetricLodRenderer.shouldSplitForDistance(false, true, 2,
                l2Boundary * 0.96, renderChunks));
        assertTrue(VolumetricLodRenderer.shouldSplitForDistance(true, true, 2,
                l2Boundary * 1.04, renderChunks));
    }

    @Test
    void progressiveFogRampNeverInverts() {
        float start = ChunkRenderer.progressiveFogStart(512F, 64F);

        assertEquals(63F, start);
        assertTrue(start < 64F);
    }
}
