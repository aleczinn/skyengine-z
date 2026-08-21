package de.skyengine.graphics.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodMeshSliceTest {

    @Test
    void drawCountCoversTheSliceBoundary() {
        int cap = LodMesh.MAX_QUADS_PER_DRAW;
        assertEquals(0, LodMesh.drawCountForQuads(0));
        assertEquals(1, LodMesh.drawCountForQuads(1));
        assertEquals(1, LodMesh.drawCountForQuads(cap));
        assertEquals(2, LodMesh.drawCountForQuads(cap + 1));
        assertEquals(3, LodMesh.drawCountForQuads(2 * cap + 17));
    }

    @Test
    void slicesCoverEveryQuadExactlyOnce() {
        int total = 2 * LodMesh.MAX_QUADS_PER_DRAW + 17;
        int cursor = 0;
        for (int draw = 0; draw < LodMesh.drawCountForQuads(total); draw++) {
            assertEquals(cursor, LodMesh.drawStartQuad(draw));
            int count = LodMesh.drawQuadCount(total, draw);
            assertTrue(count > 0 && count <= LodMesh.MAX_QUADS_PER_DRAW);
            cursor += count;
        }
        assertEquals(total, cursor);
    }

    @Test
    void baseVertexOffsetsStayQuadAligned() {
        int cap = LodMesh.MAX_QUADS_PER_DRAW;
        for (int draw = 0; draw < 4; draw++) {
            int startVertex = LodMesh.drawStartQuad(draw) * 4;
            assertEquals(0, startVertex & 3);
            assertEquals(draw * cap * 4, startVertex);
        }
    }

    @Test
    void rejectsInvalidCountsAndSliceIndices() {
        assertThrows(IllegalArgumentException.class, () -> LodMesh.drawCountForQuads(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> LodMesh.drawStartQuad(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> LodMesh.drawQuadCount(1, 1));
    }

    @Test
    void gpuCullTracksSliceDescriptorsIndependentlyWithoutGlSupport() {
        GpuCull cull = new GpuCull();
        int first = cull.addLod(1, 0, 0, 0, 0F, 256F,
                128F, 1F / 127F, DrawMetadata.LOD_REGION_SCALE_CODE, 0,
                LodMesh.MAX_QUADS_PER_DRAW * 6, 0);
        int second = cull.addLod(1, 0, 0, 0, 0F, 256F,
                128F, 1F / 127F, DrawMetadata.LOD_REGION_SCALE_CODE, 0,
                6, LodMesh.MAX_QUADS_PER_DRAW * 4);

        assertTrue(cull.hasLod());
        cull.setLodDebugConflict(first, 0x1234);
        cull.setLodDebugConflict(second, 0x1234);
        cull.removeLod(1, first);
        assertTrue(cull.hasLod());
        cull.removeLod(1, second);
        assertFalse(cull.hasLod());
    }
}
