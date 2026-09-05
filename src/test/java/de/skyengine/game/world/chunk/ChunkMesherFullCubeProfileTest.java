package de.skyengine.game.world.chunk;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkMesherFullCubeProfileTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void detailedRecorderDoesNotChangeMeshAndCountsOverlayPath() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(8, 10, 8, Blocks.GRASS_BLOCK);
        ChunkMesher.MeshData baseline = new ChunkMesher().mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);
        ChunkMesher.MeshData scalarReference = new ChunkMesher(null, null,
                ChunkMesher.VisibilityPath.SCALAR_REFERENCE).mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);

        RecordingProfile profile = new RecordingProfile();
        ChunkMesher.MeshData measured = new ChunkMesher(null, profile).mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);

        assertMeshEquals(baseline, measured);
        assertMeshEquals(scalarReference, measured);
        assertNotNull(profile.operations);
        assertEquals(6, profile.operations.visibleFaces());
        assertEquals(0, profile.operations.overlayFallbackFaces());
        assertEquals(6, profile.operations.compactQuads());
        assertEquals(39_304, profile.operations.cellsScanned());
        assertEquals(32_768, profile.operations.sectionCellsClassified());
        assertEquals(6_536, profile.operations.haloCellsClassified());
        assertEquals(6_144, profile.operations.visibilityWordsProcessed());
        assertEquals(6_144, profile.operations.neighborWordReads());
        assertEquals(0, profile.operations.faceNeighborLookups());
        assertEquals(1, profile.samples.get(ChunkMesher.FullCubePhase.STATE_CLASSIFICATION));
        assertEquals(1, profile.samples.get(ChunkMesher.FullCubePhase.VISIBILITY_WORD_DERIVATION));
        assertEquals(192, profile.samples.get(ChunkMesher.FullCubePhase.BLOCK_FACE_VISIBILITY));
    }

    @Test
    void rowMaskAndScalarReferenceMatchAcrossChunkBoundary() {
        Chunk center = new Chunk(-1, 2);
        Chunk east = new Chunk(0, 2);
        center.setBlock(31, 5, 12, Blocks.STONE);
        east.setBlock(0, 5, 12, Blocks.STONE);

        ChunkMesher.MeshData rowMask = new ChunkMesher().mesh(
                center, 0, null, null, null, east, new Chunk[4]);
        ChunkMesher.MeshData scalar = new ChunkMesher(null, null,
                ChunkMesher.VisibilityPath.SCALAR_REFERENCE).mesh(
                center, 0, null, null, null, east, new Chunk[4]);

        assertMeshEquals(scalar, rowMask);
        assertEquals(5, rowMask.stats.fullCubeFacesBeforeGreedy());
    }

    @Test
    void disabledAoProducesNoAoTimingOperations() {
        boolean previous = GameSettings.get().ambientOcclusion;
        try {
            GameSettings.get().ambientOcclusion = false;
            ChunkMesher.configure(false, GameSettings.get().leavesQuality
                    == GameSettings.LeavesQuality.LOW);
            Chunk chunk = new Chunk(0, 0);
            chunk.setBlock(8, 10, 8, Blocks.STONE);
            RecordingProfile profile = new RecordingProfile();
            new ChunkMesher(null, profile).mesh(
                    chunk, 0, null, null, null, null, new Chunk[4]);

            assertNotNull(profile.operations);
            assertEquals(0, profile.operations.aoFaces());
            assertEquals(0, profile.operations.aoOccluderLookups());
            assertEquals(0, profile.operationsByPhase.get(
                    ChunkMesher.FullCubePhase.CORNER_AO_SAMPLING));
        } finally {
            GameSettings.get().ambientOcclusion = previous;
            ChunkMesher.configure(previous, GameSettings.get().leavesQuality
                    == GameSettings.LeavesQuality.LOW);
        }
    }

    @Test
    void affineCornerMergeReportsSinglePlaneSavingsWithoutFinalRescan() {
        boolean previous = GameSettings.get().ambientOcclusion;
        GameSettings.get().ambientOcclusion = true;
        try {
            Chunk chunk = new Chunk(0, 0);
            for (int x = 5; x <= 12; x++) chunk.setBlock(x, 10, 8, Blocks.STONE);
            for (int z = 7; z <= 9; z++) for (int x = 3; x <= 14; x++) {
                chunk.light.set(x, 11, z, Math.clamp(15 - x, 0, 15));
            }
            RecordingProfile profile = new RecordingProfile();
            new ChunkMesher(null, profile).mesh(
                    chunk, 0, null, null, null, null, new Chunk[4]);

            ChunkMesher.FullCubeOperations operations = profile.operations;
            assertNotNull(operations);
            assertEquals(0, operations.compatibilityFinalCalls());
            assertTrue(operations.singlePlaneCandidates() > 0);
            assertTrue(operations.singlePlaneAccepted() > 0);
            assertTrue(operations.incrementalBorderChecks() > 0);
            assertTrue(operations.fullCandidateRescansAvoided() > 0);
            assertTrue(operations.sourceCellsAvoided() > 0);
            long histogramCalls = 0;
            for (long count : operations.compatibilityCellHistogram()) histogramCalls += count;
            assertEquals(operations.compatibilityCalls(), histogramCalls);
        } finally {
            GameSettings.get().ambientOcclusion = previous;
        }
    }

    @Test
    void isolatedProfilerMeasuresOnlySelectedEnvelope() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(8, 10, 8, Blocks.STONE);
        ChunkMesher.MeshData baseline = new ChunkMesher().mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);
        SelectiveProfile profile = new SelectiveProfile(
                ChunkMesher.FullCubePhase.MASK_SCAN_ENVELOPE);
        ChunkMesher.MeshData measured = new ChunkMesher(null, profile).mesh(
                chunk, 0, null, null, null, null, new Chunk[4]);

        assertMeshEquals(baseline, measured);
        assertEquals(1, profile.samples.getOrDefault(
                ChunkMesher.FullCubePhase.MASK_SCAN_ENVELOPE, 0L));
        assertEquals(1, profile.samples.size());
    }

    private static void assertMeshEquals(ChunkMesher.MeshData expected,
                                         ChunkMesher.MeshData actual) {
        assertArrayEquals(expected.opaque, actual.opaque);
        assertArrayEquals(expected.cutout, actual.cutout);
        assertArrayEquals(expected.translucent, actual.translucent);
        assertArrayEquals(expected.detail, actual.detail);
        for (int i = 0; i < expected.compactGeometry.length; i++) {
            assertArrayEquals(expected.compactGeometry[i], actual.compactGeometry[i]);
            assertArrayEquals(expected.compactShading[i], actual.compactShading[i]);
        }
    }

    private static final class RecordingProfile implements ChunkMesher.FullCubeProfileRecorder {
        final Map<ChunkMesher.FullCubePhase, Long> samples =
                new EnumMap<>(ChunkMesher.FullCubePhase.class);
        final Map<ChunkMesher.FullCubePhase, Long> operationsByPhase =
                new EnumMap<>(ChunkMesher.FullCubePhase.class);
        ChunkMesher.FullCubeOperations operations;

        @Override public boolean enabled() { return true; }
        @Override public boolean collectOperations() { return true; }
        @Override public boolean sampleSlice(int face, int slice) { return true; }
        @Override public void record(ChunkMesher.FullCubePhase phase, long nanos,
                                     long operations, long spans) {
            this.samples.merge(phase, 1L, Long::sum);
            this.operationsByPhase.merge(phase, operations, Long::sum);
        }
        @Override public void recordOperations(ChunkMesher.FullCubeOperations operations) {
            this.operations = operations;
        }
    }

    private static final class SelectiveProfile implements ChunkMesher.FullCubeProfileRecorder {
        final ChunkMesher.FullCubePhase target;
        final Map<ChunkMesher.FullCubePhase, Long> samples =
                new EnumMap<>(ChunkMesher.FullCubePhase.class);

        SelectiveProfile(ChunkMesher.FullCubePhase target) { this.target = target; }
        @Override public boolean enabled() { return true; }
        @Override public boolean collectOperations() { return false; }
        @Override public boolean measures(ChunkMesher.FullCubePhase phase) {
            return phase == this.target;
        }
        @Override public boolean sampleSlice(int face, int slice) { return true; }
        @Override public void record(ChunkMesher.FullCubePhase phase, long nanos,
                                     long operations, long spans) {
            this.samples.merge(phase, 1L, Long::sum);
        }
        @Override public void recordOperations(ChunkMesher.FullCubeOperations operations) {}
    }
}
