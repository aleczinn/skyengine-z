package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LodVoxelReducerTest {

    @Test
    void cellReductionKeepsImportantSilhouetteAndRgbLight() {
        long stone = LodVoxel.pack(7, 12, 2, 4, 6, 255,
                LodVoxel.PROVENANCE_SAVED, 3);
        long landmark = LodVoxel.pack(99, 15, 14, 13, 12, 96,
                LodVoxel.PROVENANCE_LIVE, 63);
        long[] children = {stone, stone, stone, stone, landmark, 0, 0, 0};

        long reduced = LodVoxelReducer.reduceCell(children);

        assertEquals(99, LodVoxel.stateId(reduced), "Landmark-Gewicht soll duenne Strukturen erhalten");
        assertEquals((4 * 255 + 96 + 4) / 8, LodVoxel.coverage(reduced));
        assertEquals(LodVoxel.PROVENANCE_LIVE, LodVoxel.provenance(reduced));
        assertTrue(LodVoxel.red(reduced) >= 2 && LodVoxel.red(reduced) <= 14);
        assertEquals(63, LodVoxel.importance(reduced));
    }

    @Test
    void eightCanonicalChildrenProduceCanonicalParent() {
        LodVoxelSection[] children = new LodVoxelSection[8];
        for (int i = 0; i < 8; i++) {
            children[i] = new LodVoxelSection(4 + (i & 1), 6 + (i >>> 2 & 1),
                    -2 + (i >>> 1 & 1), 0, LodVoxelSection.Completeness.CANONICAL);
            children[i].set(0, 0, 0, LodVoxel.pack(1, 15, 0, 0, 0, 255,
                    LodVoxel.PROVENANCE_LIVE, 1));
        }

        LodVoxelSection parent = LodVoxelReducer.reduce(children);

        assertEquals(1, parent.level);
        assertEquals(2, parent.nodeX);
        assertEquals(3, parent.nodeY);
        assertEquals(-1, parent.nodeZ);
        assertEquals(LodVoxelSection.Completeness.CANONICAL, parent.completeness());
        assertFalse(LodVoxel.isEmpty(parent.get(0, 0, 0)));
    }
}
