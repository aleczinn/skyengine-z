package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.PackedQuad;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VoxelLodMesherTest {

    private static final VoxelLodMesher.MaterialResolver MATERIALS = (state, axis, side) ->
            axis == PackedQuad.AXIS_CROSS ? null
                    : new VoxelLodMesher.Material(state, RenderLayer.OPAQUE, 0, 0, true);

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
            assertEquals(VoxelLodMesher.faceUvTransform(PackedQuad.axis(quad),
                    PackedQuad.positiveSide(quad)), PackedQuad.uvTransform(quad));
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

    @Test
    void crossPlantUsesFourCompactDoubleSidedQuads() {
        LodVoxelSection section = new LodVoxelSection(-3, 0, 7, 0,
                LodVoxelSection.Completeness.CANONICAL);
        section.set(9, 4, 12, LodVoxel.pack(2, 15, 0, 0, 0, 32,
                LodVoxel.PROVENANCE_GENERATED, 1));
        VoxelLodMesher.MaterialResolver cross = (state, axis, side) -> axis == PackedQuad.AXIS_CROSS
                ? new VoxelLodMesher.Material(19, RenderLayer.CUTOUT, 0, 0, false) : null;

        VoxelLodMesher.Mesh mesh = VoxelLodMesher.mesh(section, cross, null);

        assertEquals(4, mesh.quadCount());
        assertEquals(32, mesh.byteSize());
        for (long quad : mesh.cutout()) assertEquals(PackedQuad.AXIS_CROSS, PackedQuad.axis(quad));
    }

    @Test
    void distantFluidVolumeCollapsesToOneTopSurface() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 2,
                LodVoxelSection.Completeness.PROVISIONAL);
        long water = LodVoxel.pack(2, 15, 0, 0, 0, 255,
                LodVoxel.PROVENANCE_ANALYTIC, 12);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            section.set(x, y, z, water);
        }
        VoxelLodMesher.MaterialResolver fluids = (state, axis, side) -> state == 2
                ? new VoxelLodMesher.Material(7, RenderLayer.TRANSLUCENT, 0, 0,
                false, true) : null;

        VoxelLodMesher.Mesh mesh = VoxelLodMesher.mesh(section, fluids, null);

        assertEquals(1, mesh.quadCount());
        assertEquals(1, mesh.translucent().length);
        assertEquals(PackedQuad.AXIS_Y, PackedQuad.axis(mesh.translucent()[0]));
        assertEquals(true, PackedQuad.positiveSide(mesh.translucent()[0]));
    }

    @Test
    void sparseCoarseVoxelDoesNotBecomeFullCube() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 3,
                LodVoxelSection.Completeness.PROVISIONAL);
        section.set(4, 5, 6, LodVoxel.pack(1, 15, 0, 0, 0, 1,
                LodVoxel.PROVENANCE_GENERATED, 63));

        assertEquals(0, VoxelLodMesher.mesh(section, MATERIALS, null).quadCount());
    }

    @Test
    void finestVolumeLevelKeepsThinStructure() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 1,
                LodVoxelSection.Completeness.CANONICAL);
        section.set(4, 5, 6, LodVoxel.pack(1, 15, 0, 0, 0, 1,
                LodVoxel.PROVENANCE_LIVE, 20));

        assertEquals(6, VoxelLodMesher.mesh(section, MATERIALS, null).quadCount());
    }

    @Test
    void flatBoundaryTerrainGetsFourGreedyTransitionSkirtsInsteadOfFullWalls() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 2,
                LodVoxelSection.Completeness.PROVISIONAL);
        long solid = LodVoxel.pack(1, 15, 0, 0, 0, 255,
                LodVoxel.PROVENANCE_ANALYTIC, 4);
        for (int y = 0; y <= 5; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            section.set(x, y, z, solid);
        }

        VoxelLodMesher.Mesh mesh = VoxelLodMesher.mesh(section, MATERIALS,
                (x, y, z) -> y <= 5 ? solid : 0L);

        assertEquals(5, mesh.quadCount());
        assertEquals(1, java.util.Arrays.stream(mesh.opaque())
                .filter(quad -> PackedQuad.axis(quad) == PackedQuad.AXIS_Y).count());
        assertEquals(4, java.util.Arrays.stream(mesh.opaque())
                .filter(quad -> PackedQuad.axis(quad) != PackedQuad.AXIS_Y).count());
    }

    @Test
    void cubeFacesUseTheSameUvOrientationAsNormalBlockModels() {
        assertEquals(1, VoxelLodMesher.faceUvTransform(PackedQuad.AXIS_X, false));
        assertEquals(7, VoxelLodMesher.faceUvTransform(PackedQuad.AXIS_X, true));
        assertEquals(6, VoxelLodMesher.faceUvTransform(PackedQuad.AXIS_Y, false));
        assertEquals(0, VoxelLodMesher.faceUvTransform(PackedQuad.AXIS_Y, true));
        assertEquals(2, VoxelLodMesher.faceUvTransform(PackedQuad.AXIS_Z, false));
        assertEquals(6, VoxelLodMesher.faceUvTransform(PackedQuad.AXIS_Z, true));
    }
}
