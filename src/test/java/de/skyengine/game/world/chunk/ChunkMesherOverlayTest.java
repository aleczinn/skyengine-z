package de.skyengine.game.world.chunk;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.CompactCompositeMaterialTable;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkMesherOverlayTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void grassSidesUseOneCompactCompositeQuadWithoutLegacyFallback() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(8, 10, 8, Blocks.GRASS_BLOCK);
        long[] phaseSamples = new long[ChunkMesher.MeshPhase.values().length];

        ChunkMesher.MeshData mesh = new ChunkMesher(new ChunkMesher.MeshPhaseRecorder() {
            @Override public boolean enabled() { return true; }
            @Override public void record(ChunkMesher.MeshPhase phase, long nanos) {
                phaseSamples[phase.ordinal()]++;
            }
        }).mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);

        assertNull(mesh.opaque);
        assertNull(mesh.cutout);

        List<CompactTerrainTestView.Quad> compact = CompactTerrainTestView.quads(mesh);
        assertEquals(6, compact.size(), "all six faces must use the compact stream");
        assertEquals(List.of(0, 1, 2, 3, 4, 5),
                compact.stream().map(CompactTerrainTestView.Quad::face).sorted().toList());
        for (CompactTerrainTestView.Quad quad : compact) {
            if (quad.face() >= 2) {
                assertTrue(CompactCompositeMaterialTable.isComposite(quad.materialHandle()));
                CompactCompositeMaterialTable.Entry material =
                        CompactCompositeMaterialTable.entry(quad.materialHandle());
                assertEquals(CompactCompositeMaterialTable.MODE_CUTOUT_REPLACE, material.mode());
                assertEquals(1, material.overlayTintType());
            }
        }
        assertEquals(0, mesh.stats.overlayFallbackFaces());
        assertEquals(0, mesh.stats.overlayLegacyQuads());
        assertEquals(4, mesh.stats.compositeGrassFacesBeforeGreedy());
        assertEquals(4, mesh.stats.compositeGrassQuadsAfterGreedy());
        assertEquals(mesh.stats.compositeGrassQuadsAfterGreedy(),
                mesh.stats.compositeGrassStandardQuads()
                        + mesh.stats.compositeGrassUniformQuads()
                        + mesh.stats.compositeGrassCornerQuads());
        assertEquals(0, mesh.stats.axisAlignedQuantizedLegacyQuads(),
                "grass composite is not a legacy partial-box candidate");
        for (long samples : phaseSamples) assertEquals(1, samples, "each mesher phase is recorded once");
    }

    @Test
    void legacyReferenceStillEmitsBitIdenticalCoplanarBaseAndOverlay() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(8, 10, 8, Blocks.GRASS_BLOCK);
        ChunkMesher.MeshData mesh = new ChunkMesher(null, null,
                ChunkMesher.VisibilityPath.ROW_MASK, ChunkMesher.OverlayPath.LEGACY_REFERENCE).mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);

        assertNotNull(mesh.opaque);
        assertNotNull(mesh.cutout);
        assertEquals(mesh.opaque.length, mesh.cutout.length);
        for (int vertex = 0; vertex < 16; vertex++) {
            int offset = vertex * ChunkMesher.VERTEX_SIZE;
            assertEquals(mesh.opaque[offset], mesh.cutout[offset]);
            assertEquals(mesh.opaque[offset + 1] & 0xFFFF, mesh.cutout[offset + 1] & 0xFFFF);
        }
        assertEquals(4, mesh.stats.overlayFallbackFaces());
        assertEquals(8, mesh.stats.overlayLegacyQuads());
        assertEquals(640, mesh.stats.overlayLegacyBytes());
        assertEquals(0, mesh.stats.compositeGrassFacesBeforeGreedy());
    }

    @Test
    void grassCompositeGreedyMergeIgnoresSpatialBiomeTintChanges() {
        boolean previousAo = GameSettings.get().ambientOcclusion;
        try {
            GameSettings.get().ambientOcclusion = false;
            Chunk chunk = new Chunk(-1, 2);
            chunk.grassTintCorners = new int[33 * 33];
            for (int x = 0; x <= 32; x++) for (int z = 0; z <= 32; z++) {
                chunk.grassTintCorners[x * 33 + z] = x < 9 ? 0x336633 : 0x99BB55;
            }
            for (int x = 5; x <= 12; x++) chunk.setBlock(x, 10, 8, Blocks.GRASS_BLOCK);

            ChunkMesher.MeshData mesh = new ChunkMesher().mesh(
                    chunk, 0, null, null, null, null, new Chunk[4]);

            assertEquals(18, mesh.stats.compositeGrassFacesBeforeGreedy());
            assertEquals(4, mesh.stats.compositeGrassQuadsAfterGreedy(),
                    "north/south rows must merge across the tint-grid transition");
            assertEquals(0, mesh.stats.overlayFallbackFaces());
        } finally {
            GameSettings.get().ambientOcclusion = previousAo;
        }
    }

    @Test
    void nonOverlayTerrainIsBitIdenticalBetweenCompositeAndReferencePaths() {
        Chunk chunk = new Chunk(-2, -3);
        for (int x = 3; x < 13; x++) for (int z = 4; z < 11; z++) {
            chunk.setBlock(x, 7 + (x + z & 1), z, Blocks.STONE);
        }
        ChunkMesher.MeshData composite = new ChunkMesher().mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);
        ChunkMesher.MeshData legacy = new ChunkMesher(null, null,
                ChunkMesher.VisibilityPath.ROW_MASK, ChunkMesher.OverlayPath.LEGACY_REFERENCE).mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);

        assertArrayEquals(legacy.opaque, composite.opaque);
        assertArrayEquals(legacy.cutout, composite.cutout);
        assertArrayEquals(legacy.translucent, composite.translucent);
        assertArrayEquals(legacy.detail, composite.detail);
        for (int mode = 0; mode < 3; mode++) {
            assertArrayEquals(legacy.compactGeometry[mode], composite.compactGeometry[mode]);
            assertArrayEquals(legacy.compactShading[mode], composite.compactShading[mode]);
        }
    }
}
