package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.PackedQuad;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VoxelLodMesherTest {

    private static final VoxelLodMesher.MaterialResolver MATERIALS = (state, axis, side) ->
            new VoxelLodMesher.Material(state, RenderLayer.OPAQUE, 0, 0, true);

    @Test
    void fullNodeGreedilyCollapsesToSixEightByteQuads() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 0,
                LodVoxelSection.Completeness.CANONICAL);
        long solid = LodVoxel.pack(12, 15, 0, 0, 0, 255,
                LodVoxel.PROVENANCE_LIVE, 0);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            section.set(x, y, z, solid);
        }

        VoxelLodMesher.Mesh mesh = VoxelLodMesher.mesh(section, MATERIALS, null);

        assertEquals(6, mesh.quadCount());
        assertEquals(48, mesh.byteSize());
        for (long quad : mesh.opaque()) {
            assertEquals(32, PackedQuad.width(quad));
            assertEquals(32, PackedQuad.height(quad));
        }
    }

    @Test
    void adjacentVoxelsDoNotEmitInternalFace() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 0,
                LodVoxelSection.Completeness.CANONICAL);
        long solid = LodVoxel.pack(1, 0, 0, 0, 0, 255,
                LodVoxel.PROVENANCE_LIVE, 0);
        section.set(4, 5, 6, solid);
        section.set(5, 5, 6, solid);

        assertEquals(6, VoxelLodMesher.mesh(section, MATERIALS, null).quadCount());
    }
}
