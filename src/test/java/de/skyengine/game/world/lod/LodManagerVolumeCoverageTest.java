package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodManagerVolumeCoverageTest {

    @Test
    void levelFourFootprintMapsToWorldChunksIncludingNegativeNodes() {
        int localX = 3;
        int localZ = 9;
        LodManager.VisibleVolumeNode node = new LodManager.VisibleVolumeNode(
                new LodVolumeHierarchy.Key(-2, 0, 4, 4));

        int worldX = -2 * 16 + localX;
        int worldZ = 4 * 16 + localZ;
        assertTrue(LodManager.coversChunk(node, worldX, worldZ));
        assertTrue(LodManager.coversChunk(node, worldX + 1, worldZ));
        assertFalse(LodManager.coversChunk(node, worldX, worldZ - 16));
    }

    @Test
    void nodeNeverClaimsOutsideItsFootprint() {
        LodManager.VisibleVolumeNode node = new LodManager.VisibleVolumeNode(
                new LodVolumeHierarchy.Key(0, 1, 0, 4));
        assertTrue(LodManager.coversChunk(node, 0, 0));
        assertFalse(LodManager.coversChunk(node, 16, 0));
    }

    @Test
    void meshResultOnlyDependsOnContentAndEpoch() {
        assertFalse(LodManager.resultStillCurrent(7, 7, 41L, 42L));
        assertFalse(LodManager.resultStillCurrent(7, 8, 41L, 41L));
        assertTrue(LodManager.resultStillCurrent(7, 7, 41L, 41L));
    }

    @Test
    void meshResultAlsoBelongsToTheStillReservedRequest() {
        assertTrue(LodManager.resultStillCurrent(7, 7, 19L, 19L, 41L, 41L));
        assertFalse(LodManager.resultStillCurrent(7, 7, 18L, 19L, 41L, 41L));
        assertFalse(LodManager.resultStillCurrent(7, 7, 19L, 19L, 41L, 42L));
    }

    @Test
    void progressiveFogCanOnlyAdvanceAndNeverFallsInsideL0() {
        float first = LodManager.nextProgressiveFogEnd(0F, 512F, 768F);
        float transientNearMiss = LodManager.nextProgressiveFogEnd(first, 512F, 64F);

        assertEquals(768F, first);
        assertEquals(first, transientNearMiss);
    }

    @Test
    void coverageLatchRestoresConfiguredRange() {
        assertEquals(512F, LodManager.effectiveFogEnd(false, 64F, 512F, 4096F));
        assertEquals(768F, LodManager.effectiveFogEnd(false, 768F, 512F, 4096F));
        assertEquals(4096F, LodManager.effectiveFogEnd(true, 768F, 512F, 4096F));
    }

}
