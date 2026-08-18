package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodMesherColumnTransitionTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void l0MaskKeepsTheUnfilteredCoarseContour() {
        LodDataSource source = heightBySize(1, 64, 80);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertTrue(hasHorizontalQuadCovering(result, 31F, 16F, 80F),
                "Die L1-Randzelle darf vor dem Stitching nicht gemorpht werden");
        assertFalse(hasHorizontalQuadCovering(result, 31F, 16F, 64F));
    }

    @Test
    void measuredTransitionSubdividesCoarseEdgeInTheFineTangentGrid() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return terrain(size == 1 ? ((z & 1) == 0 ? 64 : 68) : 80);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                long interval = sampleColumn(x, z, size).interval(0);
                return LodDataSource.pack(Blocks.STONE, LodColumn.maxY(interval) - 1);
            }
        };
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 64F, 80F),
                "Die erste Hälfte der zwei Blöcke breiten L1-Kante braucht ein eigenes L0-Segment");
        assertTrue(hasVerticalSegment(result, 32F, 1F, 2F, 68F, 80F),
                "Die zweite Hälfte muss ihr eigenes L0-Höhensample verwenden");
    }

    @Test
    void emptyFineColumnNeverExtrudesTheCoarseTerrainToWorldBottom() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return size == 1 ? LodColumn.EMPTY : terrain(80);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                LodColumn column = sampleColumn(x, z, size);
                return column.size() == 0 ? LodDataSource.pack(Blocks.AIR, 0)
                        : LodDataSource.pack(Blocks.STONE, 79);
            }
        };
        LodMesher mesher = new LodMesher();
        LodMeshStats stats = new LodMeshStats();
        mesher.setStats(stats);
        LodManager.LodMeshResult result = mesher.mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertFalse(hasVerticalSegment(result, 32F, 0F, 1F, 48F, 80F));
        assertFalse(hasVerticalSegment(result, 32F, 0F, 1F, 16F, 48F));
        assertFalse(hasVerticalSegment(result, 32F, 0F, 1F, 0F, 16F),
                "Eine ungültige Feinprobe darf keine Tiefenwand bis zum Weltboden erzeugen");
        assertTrue(hasHorizontalQuadCovering(result, 32.5F, 0.5F, 80F),
                "Generatorwelten brauchen bei einer defekten Feinprobe einen lokalen Abschluss");
        assertTrue(stats.transitionProfilesMissing > 0);
        assertTrue(stats.transitionSafetyCaps > 0);
    }

    @Test
    void importedEmptyFineColumnRemainsAVoidWithoutSafetyCap() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public boolean hasWorldBottom() { return false; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return size == 1 ? LodColumn.EMPTY : terrain(80);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return size == 1 ? LodDataSource.pack(Blocks.AIR, 0)
                        : LodDataSource.pack(Blocks.STONE, 79);
            }
        };
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertFalse(hasHorizontalQuadCovering(result, 32.5F, 0.5F, 80F),
                "Importierte Leere darf nicht mit Generator-Terrain gefüllt werden");
    }

    @Test
    void measuredTransitionPreservesGapsAndDetachedIntervalsFromBothSides() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return size == 1
                        ? new LodColumn(new long[]{
                                LodColumn.pack(Blocks.STONE, 0, 20, LodColumn.FLAG_TERRAIN),
                                LodColumn.pack(Blocks.OAK_PLANKS, 40, 50, LodColumn.FLAG_LANDMARK)})
                        : new LodColumn(new long[]{
                                LodColumn.pack(Blocks.STONE, 0, 30, LodColumn.FLAG_TERRAIN),
                                LodColumn.pack(Blocks.OAK_PLANKS, 60, 70, LodColumn.FLAG_LANDMARK)});
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.OAK_PLANKS, size == 1 ? 49 : 69);
            }
        };
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 20F, 30F));
        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 40F, 50F));
        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 60F, 70F));
    }

    @Test
    void separatedTerrainIntervalsStillExposeTheirOuterTerrainTop() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return size == 1
                        ? new LodColumn(new long[]{
                        LodColumn.pack(Blocks.BEDROCK, 0, 1, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.STONE, 40, 64, LodColumn.FLAG_TERRAIN)})
                        : terrain(80);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, size == 1 ? 63 : 79);
            }
        };
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 64F, 80F),
                "Eine Höhle unter der Oberfläche darf das äußere Terrainprofil nicht verwerfen");
    }

    @Test
    void visibleDeckLayerDefinesTheOuterTransitionHeight() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return size == 1
                        ? new LodColumn(new long[]{
                        LodColumn.pack(Blocks.BEDROCK, 0, 1, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.DIRT, 1, 63, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.GRASS_BLOCK, 63, 64, LodColumn.FLAG_SKY_OPEN)})
                        : terrain(80);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(size == 1 ? Blocks.GRASS_BLOCK : Blocks.STONE,
                        size == 1 ? 63 : 79);
            }
        };
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 64F, 80F),
                "Die sichtbare Grasschicht muss die Aussenkante definieren");
        assertFalse(hasVerticalSegment(result, 32F, 0F, 1F, 63F, 80F));
    }

    @Test
    void everyLevelBoundaryHasExactlyOneTransitionOwner() {
        for (int face = 2; face <= 5; face++) {
            for (int first = 0; first < ChunkLodColumns.LEVELS; first++) {
                for (int second = 0; second < ChunkLodColumns.LEVELS; second++) {
                    LodMesher.TransitionOwnership forward =
                            LodMesher.transitionOwnership(first, second);
                    LodMesher.TransitionOwnership backward =
                            LodMesher.transitionOwnership(second, first);
                    if (first == second) {
                        assertTrue(forward == LodMesher.TransitionOwnership.REGULAR
                                && backward == LodMesher.TransitionOwnership.REGULAR,
                                "Gleiches Level muss regulaer bleiben, Face " + face);
                    } else {
                        int owners = (forward == LodMesher.TransitionOwnership.OWNED ? 1 : 0)
                                + (backward == LodMesher.TransitionOwnership.OWNED ? 1 : 0);
                        assertTrue(owners == 1, "Grenze braucht genau einen Besitzer, Face " + face
                                + ", L" + first + "/L" + second);
                        assertTrue(forward != LodMesher.TransitionOwnership.REGULAR
                                && backward != LodMesher.TransitionOwnership.REGULAR);
                    }
                }
            }
        }
        LodMesher.TransitionOwnership lodSide =
                LodMesher.maskTransitionOwnership(false, true);
        LodMesher.TransitionOwnership exactSide =
                LodMesher.maskTransitionOwnership(true, false);
        assertTrue(lodSide == LodMesher.TransitionOwnership.OWNED);
        assertTrue(exactSide == LodMesher.TransitionOwnership.FOREIGN);
    }

    @Test
    void fourRegionCornersKeepOwnershipOnEveryIncidentEdge() {
        for (int nw = 0; nw < ChunkLodColumns.LEVELS; nw++) {
            for (int ne = 0; ne < ChunkLodColumns.LEVELS; ne++) {
                for (int sw = 0; sw < ChunkLodColumns.LEVELS; sw++) {
                    for (int se = 0; se < ChunkLodColumns.LEVELS; se++) {
                        assertOwnerPair(nw, ne);
                        assertOwnerPair(nw, sw);
                        assertOwnerPair(ne, se);
                        assertOwnerPair(sw, se);
                    }
                }
            }
        }
    }

    @Test
    void l2BoundaryUsesTheFinerL1SamplePhase() {
        LodDataSource source = heightBySize(2, 64, 80);
        /* Region (1,0), Anker im Zentrum der westlichen Region: West ist L1, dieses Mesh L2. */
        LodMesher mesher = new LodMesher();
        LodMeshStats stats = new LodMeshStats();
        mesher.setStats(stats);
        LodManager.LodMeshResult result = mesher.mesh(source, new LodBlockAppearance(),
                LodConfig.of(1, 128), 2, 1, 1, 0, 0, 0, 64, 64);

        assertTrue(hasHorizontalQuadCovering(result, 1F, 32F, 80F));
        assertTrue(hasVerticalSegment(result, 0F, 32F, 34F, 64F, 80F));
        assertTrue(stats.transitionSegments > 0);
        assertTrue(stats.transitionMaxCoarseLevel == 2 && stats.transitionMaxFineLevel == 1);
        assertTrue(stats.transitionMaxCoarseSize == 4 && stats.transitionMaxFineSize == 2);
        assertTrue(stats.transitionMaxCoarseTop == 80 && stats.transitionMaxFineTop == 64);
    }

    @Test
    void negativeCoordinatesSampleImmediatelyAcrossAllFourFaces() {
        LodConfig config = LodConfig.of(16, 128);

        RecordingSource west = new RecordingSource();
        new LodMesher().mesh(west, new LodBlockAppearance(), config,
                1, 1, -1, -1, 0, 1, -64, -64);
        assertTrue(west.samples.contains(sample(-97, -128, 1)),
                "West muss boundary-fineSize sampeln");

        RecordingSource east = new RecordingSource();
        new LodMesher().mesh(east, new LodBlockAppearance(), config,
                1, 1, -1, -1, 0, 1 << 1, -64, -64);
        assertTrue(east.samples.contains(sample(-96, -128, 1)),
                "Ost muss exakt auf der Weltgrenze sampeln");

        RecordingSource north = new RecordingSource();
        new LodMesher().mesh(north, new LodBlockAppearance(), config,
                1, 1, -1, -1, 0, 1, -64, -64);
        assertTrue(north.samples.contains(sample(-128, -97, 1)),
                "Nord muss boundary-fineSize sampeln");

        RecordingSource south = new RecordingSource();
        new LodMesher().mesh(south, new LodBlockAppearance(), config,
                1, 1, -1, -1, 0, 1 << 4, -64, -64);
        assertTrue(south.samples.contains(sample(-128, -96, 1)),
                "Süd muss exakt auf der Weltgrenze sampeln");
    }

    @Test
    void naturalTerrainHasNoInternalBottomButDetachedLandmarkKeepsItsCap() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public boolean hasWorldBottom() { return false; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return new LodColumn(new long[]{
                        LodColumn.pack(Blocks.STONE, 40, 64, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.OAK_PLANKS, 90, 96, LodColumn.FLAG_LANDMARK)});
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.OAK_PLANKS, 95);
            }
        };
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 0, 64, 64);

        assertFalse(hasHorizontalAt(result, 40F), "Die Terrainhülle darf keine innere Unterseite emittieren");
        assertTrue(hasHorizontalAt(result, 90F), "Eine freistehende Landmarke braucht weiterhin eine Unterseite");
    }

    private static LodDataSource heightBySize(int fineSize, int fineTop, int coarseTop) {
        return new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return terrain(size <= fineSize ? fineTop : coarseTop);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                long interval = sampleColumn(x, z, size).interval(0);
                return LodDataSource.pack(LodColumn.state(interval), LodColumn.maxY(interval) - 1);
            }
        };
    }

    private static LodColumn terrain(int top) {
        return new LodColumn(new long[]{LodColumn.pack(Blocks.STONE, 0, top, LodColumn.FLAG_TERRAIN)});
    }

    private static void assertOwnerPair(int first, int second) {
        if (first == second) return;
        int owners = (LodMesher.transitionOwnership(first, second)
                == LodMesher.TransitionOwnership.OWNED ? 1 : 0)
                + (LodMesher.transitionOwnership(second, first)
                == LodMesher.TransitionOwnership.OWNED ? 1 : 0);
        assertTrue(owners == 1);
    }

    private static String sample(int x, int z, int size) {
        return x + ":" + z + ":" + size;
    }

    private static final class RecordingSource implements LodDataSource {
        private final List<String> samples = new ArrayList<>();

        @Override public boolean hasColumns() { return true; }
        @Override public LodColumn sampleColumn(int x, int z, int size) {
            this.samples.add(sample(x, z, size));
            return terrain(size == 1 ? 64 : 80);
        }
        @Override public long sampleSurface(int x, int z, int size) {
            return LodDataSource.pack(Blocks.STONE, size == 1 ? 63 : 79);
        }
    }

    private static boolean hasHorizontalQuadCovering(LodManager.LodMeshResult result,
                                                       float x, float z, float y) {
        int[] data = result.opaqueData();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float minX = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            boolean horizontal = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float vx = coordinate(data[p] & 0xFFFF, result.level());
                float vy = coordinate((data[p] >>> 16) & 0xFFFF, result.level()) + result.yBase();
                float vz = coordinate(data[p + 1] & 0xFFFF, result.level());
                horizontal &= close(vy, y);
                minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
                minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
            }
            if (horizontal && x >= minX && x <= maxX && z >= minZ && z <= maxZ) return true;
        }
        return false;
    }

    private static boolean hasHorizontalAt(LodManager.LodMeshResult result, float y) {
        int[] data = result.opaqueData();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean horizontal = true;
            for (int v = 0; v < 4; v++) {
                int packed = data[q + v * ChunkMesher.VERTEX_SIZE];
                horizontal &= close(coordinate((packed >>> 16) & 0xFFFF, result.level())
                        + result.yBase(), y);
            }
            if (horizontal) return true;
        }
        return false;
    }

    private static boolean hasVerticalSegment(LodManager.LodMeshResult result, float x,
                                               float minZ, float maxZ,
                                               float minY, float maxY) {
        int[] data = result.opaqueData();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean constantX = true;
            float foundMinZ = Float.POSITIVE_INFINITY, foundMaxZ = Float.NEGATIVE_INFINITY;
            float foundMinY = Float.POSITIVE_INFINITY, foundMaxY = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float vx = coordinate(data[p] & 0xFFFF, result.level());
                float vy = coordinate((data[p] >>> 16) & 0xFFFF, result.level()) + result.yBase();
                float vz = coordinate(data[p + 1] & 0xFFFF, result.level());
                constantX &= close(vx, x);
                foundMinZ = Math.min(foundMinZ, vz); foundMaxZ = Math.max(foundMaxZ, vz);
                foundMinY = Math.min(foundMinY, vy); foundMaxY = Math.max(foundMaxY, vy);
            }
            if (constantX && close(foundMinZ, minZ) && close(foundMaxZ, maxZ)
                    && close(foundMinY, minY) && close(foundMaxY, maxY)) return true;
        }
        return false;
    }

    private static float coordinate(int packed, int level) {
        return packed / LodMesher.posScaleFor(1) - 1F;
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) <= 0.01F;
    }
}
