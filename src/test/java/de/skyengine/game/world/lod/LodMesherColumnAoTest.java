package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodMesherColumnAoTest {

    private static final float TARGET_X = 64F, TARGET_Z = 64F, TARGET_TOP = 64F;

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void columnPathBakesMinecraftAoIntoExposedTerrainCorners() {
        List<Integer> colors = targetTopColors(steppedColumns(1), true, 1);

        assertEquals(List.of(0x666666, 0xCCCCCC, 0xFFFFFF, 0xCCCCCC), colors);
    }

    @Test
    void levelTwoUsesUniformAoOnTerrainTops() {
        List<Integer> colors = targetTopColors(steppedColumns(2), true, 2);

        assertEquals(1, colors.stream().distinct().count());
        assertEquals(0xCCCCCC, colors.getFirst());
    }

    @Test
    void disabledAoKeepsColumnTerrainFullyBright() {
        LodManager.LodMeshResult result = mesh(steppedColumns(1), false);
        List<Integer> colors = colorsOfHorizontalQuadsAt(result.opaqueData(), result.yBase(), TARGET_TOP);
        assertFalse(colors.isEmpty());
        assertEquals(1, colors.stream().distinct().count());
        assertEquals(0xFFFFFF, colors.getFirst());
    }

    @Test
    void uniformColumnAoStillAllowsThirtyTwoBlockGreedyRuns() {
        LodManager.LodMeshResult result = mesh(columns((x, z) -> terrain(64)), true);
        List<Integer> colors = colorsOfHorizontalQuadsAt(result.opaqueData(), result.yBase(), TARGET_TOP);

        assertEquals(16 * 4, colors.size(), "128x128 Blöcke müssen in 4x4 Top-Quads zerfallen");
        assertEquals(1, colors.stream().distinct().count());
        assertEquals(0xFFFFFF, colors.getFirst());
    }

    @Test
    void translucentWaterIntervalsDoNotOccludeColumnAo() {
        LodDataSource source = columns((x, z) -> {
            if (x == 62 && z == 64) {
                return new LodColumn(new long[]{
                        LodColumn.pack(Blocks.STONE, 0, 64, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.WATER, 64, 65, LodColumn.FLAG_SKY_OPEN)});
            }
            if (x == 64 && z == 62) return terrain(65);
            return terrain(64);
        });

        assertEquals(List.of(0xCCCCCC, 0xFFFFFF, 0xFFFFFF, 0xCCCCCC),
                targetTopColors(source, true, 1));
    }

    @Test
    void grassCliffEmitsOnlyBaseWallsWithoutLodOverlay() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 68 : 60, Blocks.GRASS_BLOCK));
        LodManager.LodMeshResult result = mesh(source, true, 2);

        assertEquals(4, countVerticalQuads(result.opaqueData(), result.yBase(), 64F, 60F, 64F),
                "Das Kontaktband muss horizontal weiterhin in vier 32er-Runs mergen");
        assertEquals(4, countVerticalQuads(result.opaqueData(), result.yBase(), 64F, 64F, 68F),
                "Das helle Wandband muss horizontal weiterhin in vier 32er-Runs mergen");
        assertEquals(0, countVerticalQuads(result.opaqueData(), result.yBase(), 64F, 60F, 68F),
                "Verschiedene AO-Baender duerfen nicht zu einem Gradienten-Quad verschmelzen");
    }

    @Test
    void columnPathBakesContactAoIntoUniformVerticalBands() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 68 : 60));
        LodManager.LodMeshResult result = mesh(source, true, 2);

        List<Integer> contact = colorsOfVerticalQuad(result.opaqueData(), result.yBase(),
                64F, 60F, 64F);
        List<Integer> upper = colorsOfVerticalQuad(result.opaqueData(), result.yBase(),
                64F, 64F, 68F);
        assertFalse(contact.isEmpty());
        assertFalse(upper.isEmpty());
        assertEquals(1, contact.stream().distinct().count(),
                "Ein AO-Band darf keine diagonale Corner-Interpolation enthalten");
        assertEquals(1, upper.stream().distinct().count(),
                "Ein AO-Band darf keine diagonale Corner-Interpolation enthalten");
        assertTrue(brightness(contact.getFirst()) < brightness(upper.getFirst()),
                "Das Kontaktband zum tieferen Terrain muss dunkler als das obere Wandband sein");
    }

    @Test
    void sameCliffUsesSoftL1AndUniformL2WallAo() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 64 : 60));
        LodManager.LodMeshResult l1 = mesh(source, true, 1);
        LodManager.LodMeshResult l2 = mesh(source, true, 2);

        List<Integer> l1Colors = colorsOfVerticalQuad(l1.opaqueData(), l1.yBase(),
                64F, 60F, 64F);
        List<Integer> l2Colors = colorsOfVerticalQuad(l2.opaqueData(), l2.yBase(),
                64F, 60F, 64F);
        assertFalse(l1Colors.isEmpty());
        assertFalse(l2Colors.isEmpty());
        assertTrue(l1Colors.stream().distinct().count() > 1,
                "L1 muss das weiche Corner-AO behalten");
        assertEquals(1, l2Colors.stream().distinct().count(),
                "L2 muss pro Rasterband einen uniformen AO-Wert verwenden");
    }

    @Test
    void mixedL1L2TransitionUsesTheEmittingLevelsAoMode() {
        LodManager.LodMeshResult l1Owner = mesh(heightBySize(2, 64, 60), true, 1,
                new LodManager.LodNeighborSnapshot(1, 1, 1, 2));
        LodManager.LodMeshResult l2Owner = mesh(heightBySize(2, 60, 64), true, 2,
                new LodManager.LodNeighborSnapshot(2, 2, 1, 2));

        List<Integer> l1Colors = colorsOfVerticalQuad(l1Owner.opaqueData(), l1Owner.yBase(),
                128F, 60F, 64F);
        List<Integer> l2Colors = colorsOfVerticalQuad(l2Owner.opaqueData(), l2Owner.yBase(),
                0F, 60F, 64F);
        assertFalse(l1Colors.isEmpty(), "Die L1-Seite muss ihre exponierte Transition emittieren");
        assertFalse(l2Colors.isEmpty(), "Die L2-Seite muss ihre exponierte Transition emittieren");
        assertTrue(l1Colors.stream().distinct().count() > 1,
                "Eine L1-eigene Transition behaelt weiches Corner-AO");
        assertEquals(1, l2Colors.stream().distinct().count(),
                "Eine L2-eigene Transition verwendet uniformes Band-AO");
    }

    @Test
    void levelOneTransitionAoDoesNotChangeItsGeometry() {
        LodDataSource source = heightBySize(2, 64, 60);
        LodManager.LodNeighborSnapshot neighbors =
                new LodManager.LodNeighborSnapshot(1, 1, 1, 2);
        LodManager.LodMeshResult withoutAo = mesh(source, false, 1, neighbors);
        LodManager.LodMeshResult withAo = mesh(source, true, 1, neighbors);

        assertEquals(verticalGeometryAtX(withoutAo.opaqueData(), 128F),
                verticalGeometryAtX(withAo.opaqueData(), 128F),
                "Auch eine L1-eigene Transition darf durch AO keine neuen Vertices erhalten");
    }

    @Test
    void disabledAoKeepsVerticalWallsDirectionallyUniform() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 64 : 62));
        LodManager.LodMeshResult result = mesh(source, false);
        List<Integer> colors = colorsOfVerticalQuad(result.opaqueData(), result.yBase(),
                64F, 62F, 64F);

        assertFalse(colors.isEmpty());
        assertEquals(1, colors.stream().distinct().count());
    }

    @Test
    void levelOneAoDoesNotChangeTheWallGeometry() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 80 : 60));
        LodManager.LodMeshResult withoutAo = mesh(source, false, 1);
        LodManager.LodMeshResult withAo = mesh(source, true, 1);

        assertEquals(verticalGeometryAtX(withoutAo.opaqueData(), 64F),
                verticalGeometryAtX(withAo.opaqueData(), 64F),
                "L1-AO darf weder Y-Schnitte noch horizontale Greedy-Grenzen veraendern");
        List<Integer> colors = colorsOfVerticalQuad(withAo.opaqueData(), withAo.yBase(),
                64F, 60F, 80F);
        assertFalse(colors.isEmpty());
        assertTrue(colors.stream().distinct().count() > 1,
                "Die unveraenderte L1-Wand muss weiterhin weiches Corner-AO tragen");
    }

    @Test
    void tallL2WallUsesCoarseGloballyAlignedAoBands() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 96 : 60));
        LodManager.LodMeshResult result = mesh(source, true, 2);

        assertTrue(hasVerticalQuad(result.opaqueData(), result.yBase(), 64F, 60F, 64F),
                "Das erste Teilband muss an der globalen 16er-Grenze enden");
        assertFalse(hasVerticalQuad(result.opaqueData(), result.yBase(), 64F, 60F, 96F),
                "L2-AO darf nicht ueber mehrere unterschiedliche Rasterbaender laufen");
        assertTrue(verticalBandEdgesAtX(result.opaqueData(), result.yBase(), 64F).stream()
                        .filter(edge -> edge > 60 && edge < 96)
                        .allMatch(edge -> edge % 16 == 0),
                "Innere L2-Bandgrenzen muessen global auf 16 Bloecke ausgerichtet sein");
    }

    private static LodDataSource steppedColumns(int level) {
        int size = 1 << level;
        return columns((x, z) -> terrain((x == TARGET_X - size && z == TARGET_Z)
                || (x == TARGET_X && z == TARGET_Z - size) ? 65 : 64));
    }

    private static List<Integer> targetTopColors(LodDataSource source, boolean ao, int level) {
        LodManager.LodMeshResult result = mesh(source, ao, level);
        int size = 1 << level;
        List<Integer> colors = colorsOfHorizontalQuad(result.opaqueData(), result.yBase(),
                TARGET_X, TARGET_Z, TARGET_X + size, TARGET_Z + size, TARGET_TOP);
        assertFalse(colors.isEmpty(), "Die AO-Zielzelle darf nicht in ein inkompatibles Greedy-Quad aufgehen");
        return colors;
    }

    private static LodManager.LodMeshResult mesh(LodDataSource source, boolean ao) {
        return mesh(source, ao, 1);
    }

    private static LodManager.LodMeshResult mesh(LodDataSource source, boolean ao, int level) {
        return mesh(source, ao, level, LodManager.LodNeighborSnapshot.sameLevel(level));
    }

    private static LodManager.LodMeshResult mesh(LodDataSource source, boolean ao, int level,
                                                  LodManager.LodNeighborSnapshot neighbors) {
        GameSettings settings = GameSettings.get();
        boolean previousAo = settings.ambientOcclusion;
        settings.ambientOcclusion = ao;
        try {
            return new LodMesher().mesh(source, new LodBlockAppearance(), LodConfig.of(16, 128),
                    level, 1, 0, 0, 0, LodManager.LodClipSnapshot.centerOnly(0),
                    neighbors, 64, 64);
        } finally {
            settings.ambientOcclusion = previousAo;
        }
    }

    private static List<Integer> colorsOfHorizontalQuad(int[] data, int yBase,
                                                         float minX, float minZ,
                                                         float maxX, float maxZ, float y) {
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float foundMinX = Float.POSITIVE_INFINITY, foundMinZ = Float.POSITIVE_INFINITY;
            float foundMaxX = Float.NEGATIVE_INFINITY, foundMaxZ = Float.NEGATIVE_INFINITY;
            boolean horizontal = true;
            List<Integer> colors = new ArrayList<>(4);
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float vx = xzCoordinate(data[p] & 0xFFFF);
                float vy = yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                float vz = xzCoordinate(data[p + 1] & 0xFFFF);
                horizontal &= close(vy, y);
                foundMinX = Math.min(foundMinX, vx);
                foundMaxX = Math.max(foundMaxX, vx);
                foundMinZ = Math.min(foundMinZ, vz);
                foundMaxZ = Math.max(foundMaxZ, vz);
                colors.add(data[p + 3] & 0xFFFFFF);
            }
            if (horizontal && close(foundMinX, minX) && close(foundMaxX, maxX)
                    && close(foundMinZ, minZ) && close(foundMaxZ, maxZ)) return colors;
        }
        return List.of();
    }

    private static List<Integer> colorsOfHorizontalQuadsAt(int[] data, int yBase, float y) {
        List<Integer> colors = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean horizontal = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                horizontal &= close(yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase, y);
            }
            if (!horizontal) continue;
            for (int v = 0; v < 4; v++) {
                colors.add(data[q + v * ChunkMesher.VERTEX_SIZE + 3] & 0xFFFFFF);
            }
        }
        return colors;
    }

    private static int countVerticalQuads(int[] data, int yBase, float expectedX,
                                          float expectedMinY, float expectedMaxY) {
        int matches = 0;
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            boolean constantX = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                constantX &= close(xzCoordinate(data[p] & 0xFFFF), expectedX);
                float y = yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (constantX && close(minY, expectedMinY) && close(maxY, expectedMaxY)) matches++;
        }
        return matches;
    }

    private static List<Integer> colorsOfVerticalQuad(int[] data, int yBase, float expectedX,
                                                       float expectedMinY, float expectedMaxY) {
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            boolean constantX = true;
            List<Integer> colors = new ArrayList<>(4);
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                constantX &= close(xzCoordinate(data[p] & 0xFFFF), expectedX);
                float y = yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                colors.add(data[p + 3] & 0xFFFFFF);
            }
            if (constantX && close(minY, expectedMinY) && close(maxY, expectedMaxY)) return colors;
        }
        return List.of();
    }

    private static boolean hasVerticalQuad(int[] data, int yBase, float expectedX,
                                           float expectedMinY, float expectedMaxY) {
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            boolean constantX = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                constantX &= close(xzCoordinate(data[p] & 0xFFFF), expectedX);
                float y = yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (constantX && close(minY, expectedMinY) && close(maxY, expectedMaxY)) return true;
        }
        return false;
    }

    /** Gepackte Geometrie + Licht aller Wände auf einer X-Ebene; RGB wird bewusst ausgelassen. */
    private static List<List<Integer>> verticalGeometryAtX(int[] data, float expectedX) {
        List<List<Integer>> result = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean constantX = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                constantX &= close(xzCoordinate(data[p] & 0xFFFF), expectedX);
            }
            if (!constantX) continue;
            List<Integer> quad = new ArrayList<>(16);
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                quad.add(data[p]);
                quad.add(data[p + 1]);
                quad.add(data[p + 2]);
                quad.add(data[p + 4]);
            }
            result.add(quad);
        }
        return result;
    }

    private static List<Integer> verticalBandEdgesAtX(int[] data, int yBase, float expectedX) {
        List<Integer> result = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean constantX = true;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                constantX &= close(xzCoordinate(data[p] & 0xFFFF), expectedX);
                int y = Math.round(yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (!constantX || minY == maxY) continue;
            result.add(minY);
            result.add(maxY);
        }
        return result;
    }

    private static int brightness(int color) {
        return (color >> 16 & 0xFF) + (color >> 8 & 0xFF) + (color & 0xFF);
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

    private static LodColumn terrain(int top) {
        return terrain(top, Blocks.STONE);
    }

    private static LodColumn terrain(int top, int block) {
        return new LodColumn(new long[]{LodColumn.pack(block, 0, top, LodColumn.FLAG_TERRAIN)});
    }

    private static LodDataSource columns(ColumnFactory factory) {
        return new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return factory.at(x, z);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                LodColumn column = factory.at(x, z);
                long top = column.interval(column.size() - 1);
                return LodDataSource.pack(LodColumn.state(top), LodColumn.maxY(top) - 1);
            }
        };
    }

    private static LodDataSource heightBySize(int fineSize, int fineTop, int coarseTop) {
        return new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return terrain(size <= fineSize ? fineTop : coarseTop);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                int top = size <= fineSize ? fineTop : coarseTop;
                return LodDataSource.pack(Blocks.STONE, top - 1);
            }
        };
    }

    @FunctionalInterface
    private interface ColumnFactory {
        LodColumn at(int x, int z);
    }
}
