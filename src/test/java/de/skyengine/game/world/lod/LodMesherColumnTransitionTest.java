package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.trees.BiomeTreeFeature;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodMesherColumnTransitionTest {

    private static final class Seed187Source implements LodDataSource {
        private final WorldGenerator generator = new AlphaWorldGeneratorV2(187);
        private final ChunkDecorator decorator = new ChunkDecorator(this.generator,
                List.of(new BiomeTreeFeature()));
        private final Map<Long, ChunkLodColumns> columns = new HashMap<>();
        private final Map<Long, Chunk> exactChunks = new HashMap<>();
        private final Map<Long, ChunkMesher.MeshData[]> exactMeshes = new HashMap<>();

        @Override public boolean hasColumns() { return true; }

        @Override
        public LodColumn sampleColumn(int x, int z, int size) {
            int level = Integer.numberOfTrailingZeros(size);
            int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
            long key = Chunk.key(cx, cz) ^ ((long) level << 56);
            ChunkLodColumns chunk = this.columns.computeIfAbsent(key, ignored ->
                    ChunkLodColumns.fromGenerator(this.generator,
                            this.decorator.decorateForLod(cx, cz), cx, cz, level));
            return chunk.get(x & ChunkSection.MASK, z & ChunkSection.MASK, size);
        }

        @Override
        public long sampleSurface(int x, int z, int size) {
            LodColumn column = this.sampleColumn(x, z, size);
            if (column.size() == 0) return LodDataSource.pack(Blocks.AIR, 0);
            long top = column.interval(column.size() - 1);
            return LodDataSource.pack(LodColumn.state(top), LodColumn.maxY(top) - 1);
        }

        @Override
        public ExactColumnSampler openExactColumnSampler() {
            return new ExactColumnSampler() {
                @Override
                public boolean sampleColumn(int x, int z, int[] target) {
                    int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
                    Chunk chunk = exactChunk(cx, cz);
                    int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
                    for (int y = 0; y < Chunk.HEIGHT; y++) {
                        target[y] = chunk.getBlock(lx, y, lz);
                    }
                    return true;
                }

                @Override
                public boolean sampleRenderedBoundaryFaces(int x, int z, int face, int[] target) {
                    java.util.Arrays.fill(target, 0);
                    int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
                    int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
                    int tangent = face == 2 || face == 3 ? lx : lz;
                    boolean onBoundary = switch (face) {
                        case 2 -> lz == 0;
                        case 3 -> lz == ChunkSection.MASK;
                        case 4 -> lx == 0;
                        case 5 -> lx == ChunkSection.MASK;
                        default -> false;
                    };
                    if (!onBoundary) return false;
                    ChunkMesher.MeshData[] meshes = exactMeshes(cx, cz);
                    int ownershipIndex = (face - 2) * ChunkSection.SIZE + tangent;
                    for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                        target[sectionY] = meshes[sectionY].boundaryFaces()[ownershipIndex];
                    }
                    return true;
                }
            };
        }

        private Chunk exactChunk(int cx, int cz) {
            return this.exactChunks.computeIfAbsent(Chunk.key(cx, cz), ignored -> {
                Chunk generated = new Chunk(cx, cz);
                this.generator.generate(generated);
                this.decorator.decorateForLod(generated);
                return generated;
            });
        }

        private ChunkMesher.MeshData[] exactMeshes(int cx, int cz) {
            return this.exactMeshes.computeIfAbsent(Chunk.key(cx, cz), ignored -> {
                Chunk center = this.exactChunk(cx, cz);
                Chunk north = this.exactChunk(cx, cz - 1);
                Chunk south = this.exactChunk(cx, cz + 1);
                Chunk west = this.exactChunk(cx - 1, cz);
                Chunk east = this.exactChunk(cx + 1, cz);
                Chunk[] diagonals = {
                        this.exactChunk(cx - 1, cz - 1), this.exactChunk(cx + 1, cz - 1),
                        this.exactChunk(cx - 1, cz + 1), this.exactChunk(cx + 1, cz + 1)
                };
                ChunkMesher mesher = new ChunkMesher();
                ChunkMesher.MeshData[] meshes = new ChunkMesher.MeshData[Chunk.SECTIONS];
                for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                    meshes[sectionY] = mesher.mesh(center, sectionY, north, south, west, east,
                            diagonals);
                }
                return meshes;
            });
        }

        int terrainTop(int x, int z, int size) {
            long terrain = ChunkLodColumns.outerTerrainInterval(this.sampleColumn(x, z, size));
            return terrain == 0 ? 0 : LodColumn.maxY(terrain);
        }
    }

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
    void seed187UnderwaterWestEdgeClosesEveryCoarseHigherBand() {
        Seed187Source source = new Seed187Source();
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0xF, 0);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, -63, -138, 0, clip,
                -8512, -17600);
        int baseZ = -138 * LodMesher.REGION_BLOCKS;
        int checked = 0;
        int firstOffset = -1, firstExactTop = 0, firstCoarseTop = 0;
        for (int offset = 0; offset < LodMesher.REGION_BLOCKS; offset++) {
            int z = baseZ + offset;
            int coarseTop = source.terrainTop(-8064, Math.floorDiv(z, 2) * 2, 2);
            int exactTop = source.terrainTop(-8065, z, 1);
            if (coarseTop <= exactTop) continue;
            checked++;
            if (firstOffset < 0) {
                firstOffset = offset;
                firstExactTop = exactTop;
                firstCoarseTop = coarseTop;
            }
            assertTrue(hasBoundaryCoverage(result, 4, 0F, offset, offset + 1F,
                            exactTop, coarseTop),
                    "Offene Seed-187-L0/L1-Naht bei Weltposition (-8064," + z
                            + "), exakt=" + exactTop + ", L1=" + coarseTop);
        }
        assertTrue(checked > 0, "Die reale Seed-187-Grenze muss den gemeldeten L1-Hoehenversatz enthalten");
        assertTrue(verticalCoverageCount(result, 0F, firstOffset + 0.5F,
                        (firstExactTop + firstCoarseTop) * 0.5F) == 2,
                "Der L0/L1-Abschluss muss von beiden Blickrichtungen sichtbar sein");
    }

    @Test
    void reportedSeed187MountainHasNoDiagonalAoGradientOnVerticalQuads() {
        GameSettings settings = GameSettings.get();
        boolean previousAo = settings.ambientOcclusion;
        settings.ambientOcclusion = true;
        try {
            int rx = -55, rz = -144;
            LodManager.LodMeshResult result = new LodMesher().mesh(new Seed187Source(),
                    new LodBlockAppearance(), LodConfig.of(16, 128), 2, 1, rx, rz, 0,
                    LodManager.LodClipSnapshot.centerOnly(0),
                    LodManager.LodNeighborSnapshot.sameLevel(2),
                    rx * LodMesher.REGION_BLOCKS + LodMesher.REGION_BLOCKS / 2,
                    rz * LodMesher.REGION_BLOCKS + LodMesher.REGION_BLOCKS / 2);

            assertValidPackedQuads(result.opaqueData());

            int checked = 0;
            int[] data = result.opaqueData();
            for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
                int firstY = (data[q] >>> 16) & 0xFFFF;
                boolean vertical = false;
                for (int v = 1; v < 4; v++) {
                    int p = q + v * ChunkMesher.VERTEX_SIZE;
                    vertical |= ((data[p] >>> 16) & 0xFFFF) != firstY;
                }
                if (!vertical) continue;
                checked++;
                int firstColor = data[q + 3] & 0xFFFFFF;
                for (int v = 1; v < 4; v++) {
                    int color = data[q + v * ChunkMesher.VERTEX_SIZE + 3] & 0xFFFFFF;
                    assertEquals(firstColor, color,
                            "Vertikales Seed-187-LOD-Quad enthaelt einen diagonalen AO-Gradienten");
                }
            }
            assertTrue(checked > 1_000, "Die Bergregion muss genuegend vertikale Regression-Quads enthalten");
            assertTrue(maxVerticalBandsPerCliffRun(data) <= 8,
                    "Die reale L2-Klippe darf nicht wieder in viele feine AO-Lamellen zerfallen");
        } finally {
            settings.ambientOcclusion = previousAo;
        }
    }

    @Test
    void reportedSeed187UnderwaterL0L1BoundariesHaveExactlyOneClosingOwner() {
        Seed187Source source = new Seed187Source();
        ReportedSeam[] seams = {
                new ReportedSeam(true, -8256, -17785),
                new ReportedSeam(true, -8480, -17514),
                new ReportedSeam(false, -17504, -8479),
                new ReportedSeam(false, -17568, -8509),
                new ReportedSeam(true, -8416, -17608)
        };

        for (ReportedSeam seam : seams) {
            assertReportedSeamClosed(source, seam, false);
            assertReportedSeamClosed(source, seam, true);
        }
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
    void outerSafetyCapsKeepEveryDirectionInsideItsPackedField() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return size >= 8 ? LodColumn.EMPTY : terrain(80);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return size >= 8 ? LodDataSource.pack(Blocks.AIR, 0)
                        : LodDataSource.pack(Blocks.STONE, 79);
            }
        };
        LodBlockAppearance appearance = new LodBlockAppearance();
        LodConfig config = LodConfig.of(1, 128);

        for (int face = 2; face <= 5; face++) {
            LodManager.LodMeshResult result = new LodMesher().mesh(source, appearance, config,
                    2, 1, 0, 0, 0, LodManager.LodClipSnapshot.centerOnly(0),
                    neighborAtFace(2, face, 3), 64, 64);
            float x = face == 4 ? -2F : face == 5 ? 130F : 2F;
            float z = face == 2 ? -2F : face == 3 ? 130F : 2F;

            assertTrue(hasHorizontalQuadCovering(result, x, z, 80F),
                    "Das aeussere Safety-Cap fehlt oder wurde beim Packen verzerrt, Face " + face);
            assertVerticesInside(result, -4F, 132F, 0F, Chunk.HEIGHT);
        }
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
        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 40F, 50F),
                "Das L0-Detail wurde gegen denselben Block im versteckten Nachbarn gecullt");
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
            assertTrue(verticalCoverageCount(eastL3, 0F, 1.5F, y + 0.5F) == 2,
                    "Jedes L2/L3-Grenzsegment braucht beide Windings, y=" + y);
            assertTrue(verticalWindingMask(eastL3, 0F, 1.5F, y + 0.5F) == 0b11,
                    "Die L2/L3-Naht muss von beiden Blickrichtungen sichtbar sein, y=" + y);
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
    void lodReconstructsOnlyTheL0WallCulledByTheHiddenExactNeighbor() {
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
            @Override public ExactColumnSampler openExactColumnSampler() {
                return exactSampler(this, (x, z, face, y) -> y >= 72 && y < 80);
            }
        };
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0, 1);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, clip, 64, 64);

        assertTrue(hasVerticalSegment(result, 128F, 0F, 1F, 64F, 72F),
                "L0 hat 64..72 gegen den spaeter ausgeblendeten exakten Nachbarn gecullt");
        assertFalse(hasVerticalSegment(result, 128F, 0F, 1F, 72F, 80F),
                "72..80 besitzt bereits das echte L0-Mesh und darf nicht koplanar doppeln");
    }

    @Test
    void openExactBackingLeavesTheAlreadyRenderedL0WallAlone() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                if (size > 1) return terrain(64);
                return x >= 128 ? terrain(80) : LodColumn.EMPTY;
            }
            @Override public long sampleSurface(int x, int z, int size) {
                LodColumn column = sampleColumn(x, z, size);
                return column.size() == 0 ? LodDataSource.pack(Blocks.AIR, 0)
                        : LodDataSource.pack(Blocks.STONE,
                        LodColumn.maxY(column.interval(column.size() - 1)) - 1);
            }
            @Override public ExactColumnSampler openExactColumnSampler() {
                return exactSampler(this, (x, z, face, y) -> y >= 64 && y < 80);
            }
        };
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0, 1);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, clip, 64, 64);

        assertFalse(hasVerticalSegment(result, 128F, 0F, 1F, 64F, 80F),
                "Gegen eine offene Backing-Spalte rendert L0 selbst; das LOD darf nicht doppeln");
    }

    @Test
    void hiddenExactWaterNeighborRestoresTheTranslucentL0Boundary() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                if (size > 1) return terrain(60);
                return new LodColumn(new long[]{
                        LodColumn.pack(Blocks.STONE, 0, 60, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.WATER, 60, 64, LodColumn.FLAG_SKY_OPEN)});
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return size > 1 ? LodDataSource.pack(Blocks.STONE, 59)
                        : LodDataSource.pack(Blocks.WATER, 63);
            }
        };
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0, 1);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, clip, 64, 64);

        assertTrue(hasTranslucentVerticalSegment(result, 128F, 0F, 1F, 60F, 64F),
                "Gegen Wasser im versteckten Nachbarn fehlt sonst die gesamte L0-Wasserwand");
    }

    @Test
    void hiddenExactNeighborClosesTheMissingL0BandOnAllRegionFaces() {
        for (int face = 2; face <= 5; face++) {
            int testedFace = face;
            LodDataSource source = new LodDataSource() {
                @Override public boolean hasColumns() { return true; }
                @Override public LodColumn sampleColumn(int x, int z, int size) {
                    if (size > 1) return terrain(64);
                    boolean visibleExact = switch (testedFace) {
                        case 2 -> z < 0;
                        case 3 -> z >= 128;
                        case 4 -> x < 0;
                        default -> x >= 128;
                    };
                    return terrain(visibleExact ? 80 : 72);
                }
                @Override public long sampleSurface(int x, int z, int size) {
                    LodColumn column = sampleColumn(x, z, size);
                    return LodDataSource.pack(Blocks.STONE,
                             LodColumn.maxY(column.interval(0)) - 1);
                }
                @Override public ExactColumnSampler openExactColumnSampler() {
                    return exactSampler(this, (x, z, exactFace, y) -> y >= 72 && y < 80);
                }
            };
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

            assertTrue(hasBoundaryCoverage(result, oppositeFace(face), boundary,
                    0F, 1F, 64F, 72F),
                    "Der gecullte L0-Anteil muss Face " + face + " schliessen");
            assertFalse(hasBoundaryCoverage(result, oppositeFace(face), boundary,
                    0F, 1F, 72F, 80F),
                    "Der bereits von L0 gerenderte Anteil darf an Face " + face + " nicht doppeln");
        }
    }

    @Test
    void hiddenExactNeighborAlsoClosesAnInternalClipBoundary() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                if (size > 1) return terrain(64);
                return terrain(x >= 32 ? 80 : 72);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                LodColumn column = sampleColumn(x, z, size);
                return LodDataSource.pack(Blocks.STONE, LodColumn.maxY(column.interval(0)) - 1);
            }
            @Override public ExactColumnSampler openExactColumnSampler() {
                return exactSampler(this, (x, z, face, y) -> y >= 72 && y < 80);
            }
        };
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertTrue(hasVerticalSegment(result, 32F, 0F, 1F, 64F, 72F));
        assertFalse(hasVerticalSegment(result, 32F, 0F, 1F, 72F, 80F));
    }

    @Test
    void exactCaveOpeningAtNegativeWorldBoundaryKeepsTheL0L1SeamClosed() {
        final int boundaryX = -8192;
        final int caveZ = -17314;
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }

            @Override
            public LodColumn sampleColumn(int x, int z, int size) {
                /* Die reduzierte size=1-Spalte besitzt absichtlich nur die aeussere Huelle.
                   Genau diese verlustbehaftete Darstellung hat die Unterwasser-Hoehle zuvor
                   verdeckt und dadurch die grobe Wand faelschlich gecullt. */
                return terrain(size == 1 ? 80 : 72);
            }

            @Override
            public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, size == 1 ? 79 : 71);
            }

            @Override
            public ExactColumnSampler openExactColumnSampler() {
                return (x, z, target) -> {
                    java.util.Arrays.fill(target, Blocks.AIR);
                    java.util.Arrays.fill(target, 0, 80, Blocks.STONE);
                    if (x >= boundaryX && z == caveZ) {
                        java.util.Arrays.fill(target, 40, 50, Blocks.AIR);
                    }
                    /* Nur die sichtbare Ostseite ist noch L0-resident. Die westliche
                       Backing-Spalte kommt bereits aus Save/Generator; ihr Inhalt muss die
                       historische Culling-Entscheidung des L0-Meshes trotzdem rekonstruieren. */
                    return x >= boundaryX;
                };
            }
        };
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0, 1 << 2);
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, -65, -136, 0, clip, 64, 64);

        assertTrue(hasVerticalSegment(result, 128F, 94F, 95F, 40F, 50F),
                "Die L1-Wand muss die echte Hoehlenoeffnung am L0-Rand schliessen");
    }

    @Test
    void staleClipContractRejectsANonResidentExactNeighbor() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) { return terrain(72); }
            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, 71);
            }
            @Override public ExactColumnSampler openExactColumnSampler() {
                return (x, z, target) -> false;
            }
        };
        LodManager.LodClipSnapshot clip = new LodManager.LodClipSnapshot(0, 0, 0, 0, 1);

        assertThrows(IllegalStateException.class, () -> new LodMesher().mesh(source,
                new LodBlockAppearance(), LodConfig.of(16, 128),
                1, 1, 0, 0, 0, clip, 64, 64));
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

    private static int oppositeFace(int face) {
        return switch (face) {
            case 2 -> 3;
            case 3 -> 2;
            case 4 -> 5;
            case 5 -> 4;
            default -> throw new IllegalArgumentException("face=" + face);
        };
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
                float vx = xzCoordinate(data[p] & 0xFFFF);
                float vy = yCoordinate((data[p] >>> 16) & 0xFFFF) + result.yBase();
                float vz = xzCoordinate(data[p + 1] & 0xFFFF);
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
                horizontal &= close(yCoordinate((packed >>> 16) & 0xFFFF)
                        + result.yBase(), y);
            }
            if (horizontal) return true;
        }
        return false;
    }

    private static boolean hasVerticalSegment(LodManager.LodMeshResult result, float x,
                                               float minZ, float maxZ,
                                               float minY, float maxY) {
        return hasVerticalSegment(result, result.opaqueData(), x, minZ, maxZ, minY, maxY);
    }

    private static boolean hasTranslucentVerticalSegment(LodManager.LodMeshResult result, float x,
                                                          float minZ, float maxZ,
                                                          float minY, float maxY) {
        return hasVerticalSegment(result, result.translucentData(), x, minZ, maxZ, minY, maxY);
    }

    private static boolean hasVerticalSegment(LodManager.LodMeshResult result, int[] data,
                                               float x, float minZ, float maxZ,
                                               float minY, float maxY) {
        List<float[]> intervals = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean constantX = true;
            float foundMinZ = Float.POSITIVE_INFINITY, foundMaxZ = Float.NEGATIVE_INFINITY;
            float foundMinY = Float.POSITIVE_INFINITY, foundMaxY = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float vx = xzCoordinate(data[p] & 0xFFFF);
                float vy = yCoordinate((data[p] >>> 16) & 0xFFFF) + result.yBase();
                float vz = xzCoordinate(data[p + 1] & 0xFFFF);
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
                float vx = xzCoordinate(data[p] & 0xFFFF);
                float vy = yCoordinate((data[p] >>> 16) & 0xFFFF) + result.yBase();
                float vz = xzCoordinate(data[p + 1] & 0xFFFF);
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
                float vx = xzCoordinate(data[p] & 0xFFFF);
                float vy = yCoordinate((data[p] >>> 16) & 0xFFFF) + result.yBase();
                float vz = xzCoordinate(data[p + 1] & 0xFFFF);
                constantX &= close(vx, x);
                minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
            }
            if (constantX && tangent > minZ + 0.01F && tangent < maxZ - 0.01F
                    && y > minY + 0.01F && y < maxY - 0.01F) count++;
        }
        return count;
    }

    private static int verticalWindingMask(LodManager.LodMeshResult result, float x,
                                           float tangent, float y) {
        return verticalWindingMask(result, result.opaqueData(), x, tangent, y)
                | verticalWindingMask(result, result.translucentData(), x, tangent, y);
    }

    private static int verticalWindingMask(LodManager.LodMeshResult result, int[] data,
                                           float x, float tangent, float y) {
        int mask = 0;
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float[] vx = new float[3];
            float[] vy = new float[3];
            float[] vz = new float[3];
            boolean constantX = true;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float px = xzCoordinate(data[p] & 0xFFFF);
                float py = yCoordinate((data[p] >>> 16) & 0xFFFF)
                        + result.yBase();
                float pz = xzCoordinate(data[p + 1] & 0xFFFF);
                constantX &= close(px, x);
                minZ = Math.min(minZ, pz); maxZ = Math.max(maxZ, pz);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                if (v < 3) {
                    vx[v] = px;
                    vy[v] = py;
                    vz[v] = pz;
                }
            }
            if (!constantX || tangent <= minZ + 0.01F || tangent >= maxZ - 0.01F
                    || y <= minY + 0.01F || y >= maxY - 0.01F) continue;
            float uy = vy[1] - vy[0], uz = vz[1] - vz[0];
            float vyEdge = vy[2] - vy[0], vzEdge = vz[2] - vz[0];
            float normalX = uy * vzEdge - uz * vyEdge;
            if (normalX > 0.0001F) mask |= 0b01;
            else if (normalX < -0.0001F) mask |= 0b10;
        }
        return mask;
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
                float x = xzCoordinate(data[p] & 0xFFFF);
                float y = yCoordinate((data[p] >>> 16) & 0xFFFF) + result.yBase();
                float z = xzCoordinate(data[p + 1] & 0xFFFF);
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

    private record ReportedSeam(boolean xAxis, int boundary, int tangent) {}

    /**
     * CPU-seitiger Schutz gegen das gemeldete Fehlerbild: Jeder LOD-Quad muss ein echtes,
     * planares Rechteck bleiben und beide EBO-Dreiecke müssen dieselbe Orientierung haben.
     * AO liegt in int3; ein versehentlich korrumpiertes Positionsfeld in int0/int1 würde
     * hier als Clamp-Sentinel, degenerierte Kante oder gegensätzliche Wicklung auffallen.
     */
    private static void assertValidPackedQuads(int[] data) {
        int vertexInts = ChunkMesher.VERTEX_SIZE;
        int quadInts = 4 * vertexInts;
        assertEquals(0, data.length % quadInts, "Unvollstaendiger LOD-Quad im Ausgabepuffer");
        for (int q = 0; q < data.length; q += quadInts) {
            int[][] p = new int[4][3];
            for (int v = 0; v < 4; v++) {
                int base = q + v * vertexInts;
                p[v][0] = data[base] & 0xFFFF;
                p[v][1] = data[base] >>> 16;
                p[v][2] = data[base + 1] & 0xFFFF;
                for (int axis = 0; axis < 3; axis++) {
                    assertTrue(p[v][axis] < 0xFFFF,
                            "LOD-Position wurde bei Quad " + q / quadInts + " geklemmt");
                }
            }

            int constantAxes = 0, varyingAxes = 0;
            for (int axis = 0; axis < 3; axis++) {
                int first = p[0][axis];
                boolean constant = true;
                int second = first;
                for (int v = 1; v < 4; v++) {
                    if (p[v][axis] != first) {
                        constant = false;
                        second = p[v][axis];
                    }
                }
                if (constant) {
                    constantAxes++;
                } else {
                    varyingAxes++;
                    for (int v = 0; v < 4; v++) {
                        assertTrue(p[v][axis] == first || p[v][axis] == second,
                                "Nicht-rechteckige LOD-Achse bei Quad " + q / quadInts);
                    }
                }
            }
            assertEquals(1, constantAxes, "LOD-Quad ist nicht planar/achsenparallel");
            assertEquals(2, varyingAxes, "LOD-Quad ist degeneriert");

            long[] firstNormal = triangleNormal(p[0], p[1], p[2]);
            long[] secondNormal = triangleNormal(p[2], p[3], p[0]);
            long firstArea = dot(firstNormal, firstNormal);
            long secondArea = dot(secondNormal, secondNormal);
            assertTrue(firstArea > 0 && secondArea > 0,
                    "Degeneriertes LOD-Dreieck bei Quad " + q / quadInts);
            assertTrue(dot(firstNormal, secondNormal) > 0,
                    "Inkonsistente LOD-Dreieckswicklung bei Quad " + q / quadInts);
        }
    }

    private static long[] triangleNormal(int[] a, int[] b, int[] c) {
        long abX = b[0] - a[0], abY = b[1] - a[1], abZ = b[2] - a[2];
        long acX = c[0] - a[0], acY = c[1] - a[1], acZ = c[2] - a[2];
        return new long[]{
                abY * acZ - abZ * acY,
                abZ * acX - abX * acZ,
                abX * acY - abY * acX
        };
    }

    private static long dot(long[] first, long[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    /** Anzahl vertikaler Teilquads auf derselben realen XZ-Wandstrecke und Materiallage. */
    private static int maxVerticalBandsPerCliffRun(int[] data) {
        Map<String, Integer> counts = new HashMap<>();
        int result = 0;
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            int minX = 0xFFFF, maxX = 0, minY = 0xFFFF, maxY = 0;
            int minZ = 0xFFFF, maxZ = 0;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                int x = data[p] & 0xFFFF;
                int y = data[p] >>> 16;
                int z = data[p + 1] & 0xFFFF;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            if (minY == maxY) continue;
            int layer = data[q + 2] >>> 16;
            String key = minX + ":" + maxX + ":" + minZ + ":" + maxZ + ":" + layer;
            result = Math.max(result, counts.merge(key, 1, Integer::sum));
        }
        return result;
    }

    /**
     * Prueft die im Spiel gemeldeten Unterwasserstellen gegen denselben Vertrag wie der
     * Renderer: Ein wirklich vorhandenes L0-Randface besitzt das Segment. Wurde es beim
     * Chunk-Meshing gegen den spaeter ausgeblendeten Nachbarchunk gecullt, muss stattdessen
     * das LOD-Uebergangsmesh genau dort eine Flaeche liefern.
     */
    private static void assertReportedSeamClosed(Seed187Source source, ReportedSeam seam,
                                                  boolean exactPositive) {
        int tangentStart = Math.floorDiv(seam.tangent, ChunkSection.SIZE) * ChunkSection.SIZE;
        int regionX = seam.xAxis ? seam.boundary : tangentStart;
        int regionZ = seam.xAxis ? tangentStart : seam.boundary;
        int rx = Math.floorDiv(regionX, LodMesher.REGION_BLOCKS);
        int rz = Math.floorDiv(regionZ, LodMesher.REGION_BLOCKS);
        int baseX = rx * LodMesher.REGION_BLOCKS;
        int baseZ = rz * LodMesher.REGION_BLOCKS;

        int exactNormal = exactPositive ? seam.boundary : seam.boundary - 1;
        int exactX = seam.xAxis ? exactNormal : tangentStart;
        int exactZ = seam.xAxis ? tangentStart : exactNormal;
        int exactCx = Math.floorDiv(exactX, ChunkSection.SIZE);
        int exactCz = Math.floorDiv(exactZ, ChunkSection.SIZE);
        int localChunkX = exactCx - rx * 4;
        int localChunkZ = exactCz - rz * 4;
        assertTrue(localChunkX >= 0 && localChunkX < 4 && localChunkZ >= 0 && localChunkZ < 4,
                "Die gemeldete Naht muss innerhalb ihrer Testregion liegen");
        int mask = 1 << (localChunkZ * 4 + localChunkX);

        LodManager.LodMeshResult result = new LodMesher().mesh(source,
                new LodBlockAppearance(), LodConfig.of(16, 128),
                1, 1, rx, rz, 0, LodManager.LodClipSnapshot.centerOnly(mask),
                baseX + LodMesher.REGION_BLOCKS / 2,
                baseZ + LodMesher.REGION_BLOCKS / 2);

        int lodFace = seam.xAxis
                ? exactPositive ? 5 : 4
                : exactPositive ? 3 : 2;
        int exactFace = oppositeFace(lodFace);
        float localBoundary = seam.boundary - (seam.xAxis ? baseX : baseZ);
        int checked = 0;
        int[] exactStates = new int[Chunk.HEIGHT];
        int[] renderedFaces = new int[Chunk.SECTIONS];
        try (LodDataSource.ExactColumnSampler sampler = source.openExactColumnSampler()) {
            for (int tangent = tangentStart; tangent < tangentStart + ChunkSection.SIZE; tangent++) {
                int sampleX = seam.xAxis ? exactNormal : tangent;
                int sampleZ = seam.xAxis ? tangent : exactNormal;
                assertTrue(sampler.sampleColumn(sampleX, sampleZ, exactStates));
                assertTrue(sampler.sampleRenderedBoundaryFaces(sampleX, sampleZ, exactFace,
                        renderedFaces));

                int coarseNormal = exactPositive ? seam.boundary - 2 : seam.boundary;
                int coarseTangent = Math.floorDiv(tangent, 2) * 2;
                int coarseX = seam.xAxis ? coarseNormal : coarseTangent;
                int coarseZ = seam.xAxis ? coarseTangent : coarseNormal;
                LodColumn coarse = source.sampleColumn(coarseX, coarseZ, 2);
                float localTangent = tangent - (seam.xAxis ? baseZ : baseX) + 0.5F;

                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    int exactState = exactStates[y];
                    int coarseState = stateAtForTest(coarse, y);
                    boolean exactExposed = faceVisibleForTest(exactState, coarseState);
                    boolean coarseExposed = faceVisibleForTest(coarseState, exactState);
                    if (!exactExposed && !coarseExposed) continue;
                    boolean l0Owns = (renderedFaces[y >> ChunkSection.SHIFT]
                            & (1 << (y & ChunkSection.MASK))) != 0;
                    if (l0Owns) continue;
                    checked++;
                    assertTrue(hasBoundaryCoverageAt(result, localBoundary, localTangent,
                                    y + 0.5F),
                            "Offene Seed-187-L0/L1-Naht bei (" + sampleX + "," + y + ","
                                    + sampleZ + "), LOD-Face=" + lodFace
                                    + ", exaktPositiv=" + exactPositive);
                }
            }
        }
        assertTrue(checked > 0, "Der Regressionstest muss mindestens ein vom Stitcher zu "
                + "uebernehmendes Segment an " + seam + " enthalten");
    }

    private static int stateAtForTest(LodColumn column, int y) {
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (y >= LodColumn.minY(interval) && y < LodColumn.maxY(interval)) {
                return LodColumn.state(interval);
            }
        }
        return Blocks.AIR;
    }

    private static boolean faceVisibleForTest(int ownStateId, int neighborStateId) {
        if (ownStateId == Blocks.AIR) return false;
        var own = Blocks.getState(ownStateId);
        var neighbor = Blocks.getState(neighborStateId);
        if (own.isFluid()) {
            return !neighbor.isOpaqueCube()
                    && !(neighbor.isFluid() && neighbor.getBlock() == own.getBlock());
        }
        return !neighbor.isOpaqueCube()
                && !(neighbor.getBlock() == own.getBlock() && own.cullsSameBlock());
    }

    private static boolean hasBoundaryCoverageAt(LodManager.LodMeshResult result,
                                                  float boundary, float tangent, float y) {
        return hasBoundaryCoverageAt(result, result.opaqueData(), boundary, tangent, y)
                || hasBoundaryCoverageAt(result, result.translucentData(), boundary, tangent, y);
    }

    private static boolean hasBoundaryCoverageAt(LodManager.LodMeshResult result, int[] data,
                                                  float boundary, float tangent, float y) {
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float x = xzCoordinate(data[p] & 0xFFFF);
                float vertexY = yCoordinate((data[p] >>> 16) & 0xFFFF)
                        + result.yBase();
                float z = xzCoordinate(data[p + 1] & 0xFFFF);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, vertexY); maxY = Math.max(maxY, vertexY);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            boolean onX = close(minX, boundary) && close(maxX, boundary)
                    && tangent > minZ + 0.01F && tangent < maxZ - 0.01F;
            boolean onZ = close(minZ, boundary) && close(maxZ, boundary)
                    && tangent > minX + 0.01F && tangent < maxX - 0.01F;
            if ((onX || onZ) && y > minY + 0.01F && y < maxY - 0.01F) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface BoundaryOwnership {
        boolean rendered(int x, int z, int face, int y);
    }

    private static LodDataSource.ExactColumnSampler exactSampler(
            LodDataSource source, BoundaryOwnership ownership) {
        return new LodDataSource.ExactColumnSampler() {
            @Override
            public boolean sampleColumn(int x, int z, int[] target) {
                java.util.Arrays.fill(target, Blocks.AIR);
                LodColumn column = source.sampleColumn(x, z, 1);
                for (int i = 0; i < column.size(); i++) {
                    long interval = column.interval(i);
                    java.util.Arrays.fill(target, LodColumn.minY(interval),
                            LodColumn.maxY(interval), LodColumn.state(interval));
                }
                return true;
            }

            @Override
            public boolean sampleRenderedBoundaryFaces(int x, int z, int face, int[] target) {
                java.util.Arrays.fill(target, 0);
                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    if (ownership.rendered(x, z, face, y)) {
                        target[y >> ChunkSection.SHIFT] |= 1 << (y & ChunkSection.MASK);
                    }
                }
                return true;
            }
        };
    }

    private static void assertVerticesInside(LodManager.LodMeshResult result,
                                             float minXZ, float maxXZ,
                                             float minY, float maxY) {
        for (int[] data : new int[][]{result.opaqueData(), result.translucentData()}) {
            for (int p = 0; p < data.length; p += ChunkMesher.VERTEX_SIZE) {
                float x = xzCoordinate(data[p] & 0xFFFF);
                float y = yCoordinate((data[p] >>> 16) & 0xFFFF) + result.yBase();
                float z = xzCoordinate(data[p + 1] & 0xFFFF);
                assertTrue(x >= minXZ - 0.01F && x <= maxXZ + 0.01F,
                        "X ausserhalb des LOD-Packungsvertrags: " + x);
                assertTrue(z >= minXZ - 0.01F && z <= maxXZ + 0.01F,
                        "Z ausserhalb des LOD-Packungsvertrags: " + z);
                assertTrue(y >= minY - 0.01F && y <= maxY + 0.01F,
                        "Y ausserhalb des LOD-Packungsvertrags: " + y);
            }
        }
    }

    private static float xzCoordinate(int packed) {
        return packed / LodMesher.posScaleFor(1) - LodMesher.XZ_POSITION_BIAS;
    }

    private static float yCoordinate(int packed) {
        return packed / LodMesher.posScaleFor(1) - 1F;
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) <= 0.01F;
    }
}
