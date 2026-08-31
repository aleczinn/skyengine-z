package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ChunkMesherOverlayTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void grassSidesKeepDepthIdenticalLegacyBaseAndOverlayGeometry() {
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

        assertNotNull(mesh.opaque);
        assertNotNull(mesh.cutout);
        assertEquals(4 * 4 * ChunkMesher.VERTEX_SIZE, mesh.opaque.length,
                "four side bases must use the legacy opaque stream");
        assertEquals(mesh.opaque.length, mesh.cutout.length,
                "every side base must have exactly one overlay quad");

        for (int vertex = 0; vertex < 16; vertex++) {
            int offset = vertex * ChunkMesher.VERTEX_SIZE;
            assertEquals(mesh.opaque[offset], mesh.cutout[offset],
                    "packed X/Y position must be bit-identical");
            assertEquals(mesh.opaque[offset + 1] & 0xFFFF, mesh.cutout[offset + 1] & 0xFFFF,
                    "packed Z position must be bit-identical");
        }

        List<CompactTerrainTestView.Quad> compact = CompactTerrainTestView.quads(mesh);
        assertEquals(2, compact.size(), "top and bottom remain compact");
        assertEquals(List.of(0, 1), compact.stream().map(CompactTerrainTestView.Quad::face).sorted().toList());
        assertEquals(4, mesh.stats.overlayFallbackFaces());
        assertEquals(0, mesh.stats.axisAlignedQuantizedLegacyQuads(),
                "grass base/overlay fallback is not a partial-box packing candidate");
        for (long samples : phaseSamples) assertEquals(1, samples, "each mesher phase is recorded once");
    }
}
