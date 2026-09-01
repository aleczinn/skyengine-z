package de.skyengine.game.world.block.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CompactCompositeMaterialTableTest {

    @Test
    void directAndCompositeHandlesRemainDisjointAndDescriptorsAreInterned() {
        int direct = CompactCompositeMaterialTable.directHandle(123);
        assertFalse(CompactCompositeMaterialTable.isComposite(direct));

        CompactCompositeMaterialTable.Entry entry = new CompactCompositeMaterialTable.Entry(
                11, 12, BakedQuad.TINT_GRASS, 0x73A95B,
                CompactCompositeMaterialTable.MODE_CUTOUT_REPLACE);
        int first = CompactCompositeMaterialTable.intern(entry);
        int second = CompactCompositeMaterialTable.intern(entry);
        assertEquals(first, second);
        assertTrue(CompactCompositeMaterialTable.isComposite(first));
        assertEquals(entry, CompactCompositeMaterialTable.entry(first));

        int[] gpu = CompactCompositeMaterialTable.gpuSnapshot();
        int offset = CompactCompositeMaterialTable.compositeIndex(first) * 4;
        assertArrayEquals(new int[] {11, 12,
                        CompactCompositeMaterialTable.MODE_CUTOUT_REPLACE
                                | BakedQuad.TINT_GRASS << 8, 0x73A95B},
                new int[] {gpu[offset], gpu[offset + 1], gpu[offset + 2], gpu[offset + 3]});
    }

    @Test
    void highBitCannotBeUsedByDirectTextureLayers() {
        assertThrows(IllegalArgumentException.class,
                () -> CompactCompositeMaterialTable.directHandle(0x8000));
    }
}
