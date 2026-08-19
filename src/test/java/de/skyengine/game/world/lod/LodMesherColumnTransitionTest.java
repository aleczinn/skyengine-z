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
        assertFalse(hasVerticalSegment(result, 32F, 0F, 1F, 40F, 50F),
                "Details auf der exakten L0-Seite gehoeren dem L0-Mesh und duerfen nicht doppeln");
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
        assertFalse(hasVerticalSegment(result, 32F, 0F, 1F, 0F, 60F),
                "Der begrenzte Nahtabschluss darf nicht bis zum Weltboden reichen");
    }

    @Test
    void bothLevelSidesRecognizeTheSameMeasuredTransition() {
        for (int face = 2; face <= 5; face++) {
            for (int first = 0; first < ChunkLodColumns.LEVELS; first++) {
                for (int second = 0; second < ChunkLodColumns.LEVELS; second++) {
                    boolean expected = first != second;
                    assertTrue(LodMesher.resolutionTransition(first, second) == expected,
                            "Vorwaertsvertrag an Face " + face);
                    assertTrue(LodMesher.resolutionTransition(second, first) == expected,
                            "Rueckwaertsvertrag an Face " + face);
                }
            }
        }
        assertTrue(LodMesher.ownsMaskTransition(false, true));
        assertFalse(LodMesher.ownsMaskTransition(true, false));
    }

    @Test
    void fourRegionCornersAgreeOnEveryIncidentTransition() {
        for (int nw = 0; nw < ChunkLodColumns.LEVELS; nw++) {
            for (int ne = 0; ne < ChunkLodColumns.LEVELS; ne++) {
                for (int sw = 0; sw < ChunkLodColumns.LEVELS; sw++) {
                    for (int se = 0; se < ChunkLodColumns.LEVELS; se++) {
                        assertTransitionPair(nw, ne);
                        assertTransitionPair(nw, sw);
                        assertTransitionPair(ne, se);
                        assertTransitionPair(sw, se);
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
        assertFalse(hasVerticalSegment(result, 0F, 32F, 34F, 56F, 64F),
                "Unterhalb beider belegten Terrainhuellen darf kein Blind-Skirt entstehen");
        assertFalse(hasVerticalSegment(result, 0F, 32F, 34F, 0F, 56F));
    }

    @Test
    void l2L3BoundaryIsOwnedByTheActuallyExposedMaterialSide() {
        LodDataSource source = heightBySize(4, 64, 80);
        LodBlockAppearance appearance = new LodBlockAppearance();
        LodConfig config = LodConfig.of(1, 128);
        LodManager.LodClipSnapshot clip = LodManager.LodClipSnapshot.centerOnly(0);

        LodManager.LodMeshResult westL2 = new LodMesher().mesh(source, appearance, config,
                2, 1, 0, 0, 0, clip,
                new LodManager.LodNeighborSnapshot(2, 2, 2, 3), 64, 64);
        LodManager.LodMeshResult eastL3 = new LodMesher().mesh(source, appearance, config,
                3, 1, 1, 0, 0, clip,
                new LodManager.LodNeighborSnapshot(3, 3, 2, 3), 64, 64);

        assertFalse(hasVerticalSegment(westL2, 128F, 0F, 4F, 64F, 80F),
                "Die niedrigere L2-Seite besitzt keine fremde L3-Wand");
        assertTrue(hasVerticalSegment(eastL3, 0F, 0F, 4F, 64F, 80F),
                "Die hoeher belegte L3-Seite muss ihre eigene Aussenwand vollstaendig liefern");
        for (int y = 64; y < 80; y++) {
            assertTrue(verticalCoverageCount(eastL3, 0F, 1.5F, y + 0.5F) == 1,
                    "Jedes L2/L3-Grenzsegment darf genau einmal vorkommen, y=" + y);
        }
    }

    @Test
    void l2L3CoverageIsClosedOnAllFacesInBothHeightDirections() {
        LodConfig config = LodConfig.of(1, 128);
        LodBlockAppearance appearance = new LodBlockAppearance();
        for (int face = 2; face <= 5; face++) {
            LodManager.LodNeighborSnapshot l3Neighbor = neighborAtFace(3, face, 2);
            LodManager.LodMeshResult highL3 = new LodMesher().mesh(
                    heightBySize(4, 64, 80), appearance, config,
                    3, 1, 0, 0, 0, LodManager.LodClipSnapshot.centerOnly(0),
                    l3Neighbor, 64, 64);
            float boundary = face == 2 || face == 4 ? 0F : 128F;
            assertTrue(hasBoundaryCoverage(highL3, face, boundary, 0F, 4F, 64F, 80F),
                    "Hoehere L3-Seite muss Face " + face + " schliessen");

            LodManager.LodNeighborSnapshot l2Neighbor = neighborAtFace(2, face, 3);
            LodManager.LodMeshResult highL2 = new LodMesher().mesh(
                    heightBySize(4, 80, 64), appearance, config,
                    2, 1, 0, 0, 0, LodManager.LodClipSnapshot.centerOnly(0),
                    l2Neighbor, 64, 64);
            assertTrue(hasBoundaryCoverage(highL2, face, boundary, 0F, 4F, 64F, 80F),
                    "Hoehere L2-Seite muss Face " + face + " schliessen");
        }
    }

    @Test
    void transitionWallUsesTheRealMaterialAtEveryHeight() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                if (size <= 4) return terrain(64);
                return new LodColumn(new long[]{
                        LodColumn.pack(Blocks.STONE, 0, 70, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.DIRT, 70, 79, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.GRASS_BLOCK, 79, 80, LodColumn.FLAG_SKY_OPEN)});
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(size <= 4 ? Blocks.STONE : Blocks.GRASS_BLOCK,
                        size <= 4 ? 63 : 79);
            }
        };
        LodBlockAppearance appearance = new LodBlockAppearance();
        LodManager.LodMeshResult result = new LodMesher().mesh(source, appearance,
                LodConfig.of(1, 128), 3, 1, 1, 0, 0,
                LodManager.LodClipSnapshot.centerOnly(0),
                new LodManager.LodNeighborSnapshot(3, 3, 2, 3), 64, 64);

        assertTrue(hasVerticalSegmentWithLayer(result, 0F, 0F, 4F, 64F, 70F,
                appearance.sideLayer(Blocks.STONE)));
        assertTrue(hasVerticalSegmentWithLayer(result, 0F, 0F, 4F, 70F, 79F,
                appearance.sideLayer(Blocks.DIRT)));
        assertTrue(hasVerticalSegmentWithLayer(result, 0F, 0F, 4F, 79F, 80F,
                appearance.sideLayer(Blocks.GRASS_BLOCK)));
        assertFalse(hasVerticalSegmentWithLayer(result, 0F, 0F, 4F, 64F, 79F,
                appearance.sideLayer(Blocks.GRASS_BLOCK)),
                "Grass darf niemals als tiefer Skirt vor Stone oder Dirt liegen");
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

    @Test
    void exactChunkAcrossRegionBoundaryGetsMeasuredTransitionWithoutBlindGuard() {
        LodDataSource source = heightBySize(1, 64, 80);
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0, 1);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, clip, 64, 64);

        assertTrue(hasVerticalSegment(result, 128F, 0F, 1F, 64F, 80F),
                "Ein L0-Chunk direkt ausserhalb der Region muss im Clip-Halo sichtbar sein");
        assertFalse(hasVerticalSegment(result, 128F, 0F, 1F, 60F, 64F),
                "Unter der L0-Oberflaeche besitzt bereits das exakte Chunk-Mesh die Aussenwand");
    }

    @Test
    void exactL0OwnsItsWholeExteriorWallWithoutCoplanarLodDuplicate() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                if (size > 1) return terrain(64);
                return terrain(x >= 128 ? 80 : 72);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                LodColumn column = sampleColumn(x, z, size);
                return LodDataSource.pack(Blocks.STONE, LodColumn.maxY(column.interval(0)) - 1);
            }
        };
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0, 1);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, clip, 64, 64);

        assertFalse(hasVerticalSegment(result, 128F, 0F, 1F, 64F, 80F),
                "Der exakte L0-Rand rendert gegen den fehlenden Nachbarn bereits die ganze Wand");
    }

    @Test
    void exactClipHaloClosesAllFourRegionFaces() {
        LodDataSource source = heightBySize(1, 64, 80);
        for (int face = 2; face <= 5; face++) {
            LodManager.LodClipSnapshot clip = switch (face) {
                case 2 -> new LodManager.LodClipSnapshot(0, 1, 0, 0, 0);
                case 3 -> new LodManager.LodClipSnapshot(0, 0, 1, 0, 0);
                case 4 -> new LodManager.LodClipSnapshot(0, 0, 0, 1, 0);
                default -> new LodManager.LodClipSnapshot(0, 0, 0, 0, 1);
            };
            LodManager.LodMeshResult result = new LodMesher().mesh(source,
                    new LodBlockAppearance(), LodConfig.of(16, 128),
                    1, 1, 0, 0, 0, clip, 64, 64);
            float boundary = face == 2 || face == 4 ? 0F : 128F;
            assertTrue(hasBoundaryCoverage(result, face, boundary, 0F, 1F, 64F, 80F),
                    "Clip-Halo muss Face " + face + " lueckenlos schliessen");
        }
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

    private static LodManager.LodNeighborSnapshot neighborAtFace(int ownLevel,
                                                                  int face,
                                                                  int neighborLevel) {
        return new LodManager.LodNeighborSnapshot(
                face == 2 ? neighborLevel : ownLevel,
                face == 3 ? neighborLevel : ownLevel,
                face == 4 ? neighborLevel : ownLevel,
                face == 5 ? neighborLevel : ownLevel);
    }

    private static void assertTransitionPair(int first, int second) {
        boolean expected = first != second;
        assertTrue(LodMesher.resolutionTransition(first, second) == expected);
        assertTrue(LodMesher.resolutionTransition(second, first) == expected);
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
        List<float[]> intervals = new ArrayList<>();
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
                    && foundMaxY > minY && foundMinY < maxY) {
                intervals.add(new float[]{foundMinY, foundMaxY});
            }
        }
        intervals.sort(java.util.Comparator.comparingDouble(interval -> interval[0]));
        float covered = minY;
        for (float[] interval : intervals) {
            if (interval[1] <= covered + 0.01F) continue;
            if (interval[0] > covered + 0.01F) return false;
            covered = Math.max(covered, interval[1]);
            if (covered >= maxY - 0.01F) return true;
        }
        return false;
    }

    private static boolean hasVerticalSegmentWithLayer(LodManager.LodMeshResult result, float x,
                                                        float minZ, float maxZ,
                                                        float minY, float maxY, int layer) {
        int[] data = result.opaqueData();
        List<float[]> intervals = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            if ((data[q + 2] >>> 16) != layer) continue;
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
                    && foundMaxY > minY && foundMinY < maxY) {
                intervals.add(new float[]{foundMinY, foundMaxY});
            }
        }
        intervals.sort(java.util.Comparator.comparingDouble(interval -> interval[0]));
        float covered = minY;
        for (float[] interval : intervals) {
            if (interval[1] <= covered + 0.01F) continue;
            if (interval[0] > covered + 0.01F) return false;
            covered = Math.max(covered, interval[1]);
            if (covered >= maxY - 0.01F) return true;
        }
        return false;
    }

    private static int verticalCoverageCount(LodManager.LodMeshResult result, float x,
                                             float tangent, float y) {
        int count = 0;
        int[] data = result.opaqueData();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean constantX = true;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float vx = coordinate(data[p] & 0xFFFF, result.level());
                float vy = coordinate((data[p] >>> 16) & 0xFFFF, result.level()) + result.yBase();
                float vz = coordinate(data[p + 1] & 0xFFFF, result.level());
                constantX &= close(vx, x);
                minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
            }
            if (constantX && tangent > minZ + 0.01F && tangent < maxZ - 0.01F
                    && y > minY + 0.01F && y < maxY - 0.01F) count++;
        }
        return count;
    }

    private static boolean hasBoundaryCoverage(LodManager.LodMeshResult result, int face,
                                               float boundary, float minT, float maxT,
                                               float minY, float maxY) {
        int[] data = result.opaqueData();
        List<float[]> intervals = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean onBoundary = true;
            float foundMinT = Float.POSITIVE_INFINITY, foundMaxT = Float.NEGATIVE_INFINITY;
            float foundMinY = Float.POSITIVE_INFINITY, foundMaxY = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float x = coordinate(data[p] & 0xFFFF, result.level());
                float y = coordinate((data[p] >>> 16) & 0xFFFF, result.level()) + result.yBase();
                float z = coordinate(data[p + 1] & 0xFFFF, result.level());
                float normal = face == 4 || face == 5 ? x : z;
                float tangent = face == 4 || face == 5 ? z : x;
                onBoundary &= close(normal, boundary);
                foundMinT = Math.min(foundMinT, tangent);
                foundMaxT = Math.max(foundMaxT, tangent);
                foundMinY = Math.min(foundMinY, y);
                foundMaxY = Math.max(foundMaxY, y);
            }
            if (onBoundary && close(foundMinT, minT) && close(foundMaxT, maxT)
                    && foundMaxY > minY && foundMinY < maxY) {
                intervals.add(new float[]{foundMinY, foundMaxY});
            }
        }
        intervals.sort(java.util.Comparator.comparingDouble(interval -> interval[0]));
        float covered = minY;
        for (float[] interval : intervals) {
            if (interval[1] <= covered + 0.01F) continue;
            if (interval[0] > covered + 0.01F) return false;
            covered = Math.max(covered, interval[1]);
            if (covered >= maxY - 0.01F) return true;
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
