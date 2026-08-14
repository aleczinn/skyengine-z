package de.skyengine.graphics.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DrawMetadataTest {

    @Test
    void roundTripsEveryLodLevelWithFullConflictMask() {
        for (int level = 0; level <= 5; level++) {
            float packed = DrawMetadata.pack(level, DrawMetadata.LOD_REGION_SCALE_CODE, 0xFFFF);
            assertEquals(level, DrawMetadata.level(packed));
            assertEquals(0xFFFF, DrawMetadata.conflictMask(packed));
            assertEquals(DrawMetadata.LOD_REGION_SCALE_CODE, DrawMetadata.positionScaleCode(packed));
        }
    }

    @Test
    void keepsSuperregionScaleSeparateFromCellMask() {
        float packed = DrawMetadata.pack(5, DrawMetadata.LOD_SUPER_SCALE_CODE, 0xA55A);
        assertEquals(5, DrawMetadata.level(packed));
        assertEquals(0xA55A, DrawMetadata.conflictMask(packed));
        assertEquals(DrawMetadata.LOD_SUPER_SCALE_CODE, DrawMetadata.positionScaleCode(packed));
    }

    @Test
    void rejectsValuesThatWouldLeaveThePackedLayout() {
        assertThrows(IllegalArgumentException.class, () -> DrawMetadata.pack(8, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> DrawMetadata.pack(0, 16, 0));
    }
}
