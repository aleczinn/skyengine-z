package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodVoxelSectionTest {

    @Test
    void paletteCompressionPreservesValuesAcrossWordBoundaries() {
        LodVoxelSection section = new LodVoxelSection(-2, 3, 5, 2,
                LodVoxelSection.Completeness.PROVISIONAL);
        long[] values = {0L, 0x1234_5678_9ABC_DEF0L, 17L, -9L, 0x55AA55AA55AA55AAL};
        for (int i = 0; i < LodVoxelSection.VOLUME; i++) {
            section.set(i & 31, i >>> 10, i >>> 5 & 31, values[i % values.length]);
        }

        section.compact();

        assertTrue(section.estimatedBytes() < LodVoxelSection.VOLUME * Long.BYTES / 8L);
        for (int i = 0; i < LodVoxelSection.VOLUME; i++) {
            assertEquals(values[i % values.length], section.get(i & 31, i >>> 10, i >>> 5 & 31));
        }
    }

    @Test
    void mutationAfterCompressionExpandsWithoutLosingOtherCells() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 0,
                LodVoxelSection.Completeness.CANONICAL);
        section.set(31, 31, 31, 42L);
        section.compact();

        section.set(7, 8, 9, 99L);

        assertEquals(42L, section.get(31, 31, 31));
        assertEquals(99L, section.get(7, 8, 9));
        assertEquals(0L, section.get(0, 0, 0));
    }
}
