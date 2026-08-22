package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

        assertEquals(List.of(0xCCCCCC, 0xFFFFFF, 0xCCCCCC, 0x666666), colors);
    }

    @Test
    void levelOneAoIgnoresFineReliefHiddenByItsReducedCells() {
        LodDataSource source = fineReliefWithL1Columns((x, z) -> terrain(64));
        LodManager.LodMeshResult result = mesh(source, true, 1,
                LodManager.LodNeighborSnapshot.sameLevel(1),
                LodManager.LodClipSnapshot.centerOnly(1 << 5));

        List<Integer> colors = colorsOfHorizontalQuadsAt(
                result.opaqueData(), result.yBase(), 64F);
        assertFalse(colors.isEmpty());
        assertEquals(1, colors.stream().distinct().count(),
                "L0-Unebenheiten unter einer ebenen L1-Wiese duerfen keine AO-Linien erzeugen");
        assertEquals(0xFFFFFF, colors.getFirst());
    }

    @Test
    void levelOneAoStillUsesVisibleReducedHeightDifferences() {
        LodDataSource source = fineReliefWithL1Columns((x, z) -> terrain(
                (x == TARGET_X - 2 && z == TARGET_Z)
                        || (x == TARGET_X && z == TARGET_Z - 2) ? 65 : 64));

        assertEquals(List.of(0xCCCCCC, 0xFFFFFF, 0xCCCCCC, 0x666666),
                targetTopColors(source, true, 1));
    }

    @Test
    void levelOneWallAoIgnoresFineReliefHiddenBelowItsSixteenBySixteenGrid() {
        ColumnFactory reducedCliff = (x, z) -> terrain(x < 64 ? 80 : 60);
        LodManager.LodMeshResult clean = mesh(columns(reducedCliff), true, 1);
        LodManager.LodMeshResult noisyL0 = mesh(
                fineReliefWithL1Columns(reducedCliff), true, 1);

        assertArrayEquals(clean.opaqueData(), noisyL0.opaqueData(),
                "L1-Wand-AO darf keine unter dem 16x16-Raster verborgenen L0-Hoehen lesen");
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
                64F, 60F, 61F);
        List<Integer> l1UpperColors = colorsOfVerticalQuad(l1.opaqueData(), l1.yBase(),
                64F, 61F, 64F);
        List<Integer> l2Colors = colorsOfVerticalQuad(l2.opaqueData(), l2.yBase(),
                64F, 60F, 64F);
        assertFalse(l1Colors.isEmpty());
        assertFalse(l1UpperColors.isEmpty());
        assertFalse(l2Colors.isEmpty());
        assertTrue(l1Colors.stream().distinct().count() > 1,
                "L1 muss das weiche Corner-AO lokal an der Kontaktkante behalten");
        assertEquals(1, l1UpperColors.stream().distinct().count(),
                "Oberhalb der Kontaktkante darf L1 keinen Wandgradienten fortsetzen");
        assertEquals(1, l2Colors.stream().distinct().count(),
                "L2 muss pro Rasterband einen uniformen AO-Wert verwenden");
    }

    @Test
    void mixedL1L2TransitionUsesTheEmittingLevelsAoMode() {
        LodManager.LodMeshResult l1Owner = mesh(
                columns((x, z) -> terrain(x < 128 ? 64 : 60)), true, 1,
                new LodManager.LodNeighborSnapshot(1, 1, 1, 2));
        LodManager.LodMeshResult l2Owner = mesh(heightBySize(2, 60, 64), true, 2,
                new LodManager.LodNeighborSnapshot(2, 2, 1, 2));

        List<Integer> l1Colors = colorsOfVerticalQuad(l1Owner.opaqueData(), l1Owner.yBase(),
                128F, 60F, 61F);
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
    void levelOneTransitionAoWrapsTheVisibleReducedEdge() {
        LodDataSource source = columns((x, z) -> terrain(x < 128 ? 64 : 60));
        LodManager.LodNeighborSnapshot neighbors =
                new LodManager.LodNeighborSnapshot(1, 1, 1, 2);
        LodManager.LodMeshResult withAo = mesh(source, true, 1, neighbors);

        assertFalse(hasVerticalQuad(withAo.opaqueData(), withAo.yBase(), 128F, 60F, 64F),
                "Corner-AO darf nicht ueber die gesamte L1-Transition gestreckt werden");
        assertTrue(hasVerticalQuad(withAo.opaqueData(), withAo.yBase(), 128F, 60F, 61F),
                "Die Kontaktkante braucht eine eigene 2x1-AO-Zelle");
        assertTrue(colorsOfVerticalQuad(withAo.opaqueData(), withAo.yBase(),
                        128F, 60F, 61F).stream().distinct().count() > 1,
                "Die sichtbare L1-Kante muss weiches Corner-AO behalten");
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
    void levelOneWallAoWrapsTheReducedContactEdgeLikeL0() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 80 : 60));
        LodManager.LodMeshResult withoutAo = mesh(source, false, 1);
        LodManager.LodMeshResult withAo = mesh(source, true, 1);

        assertEquals(4, countVerticalQuads(withoutAo.opaqueData(), withoutAo.yBase(),
                64F, 60F, 80F), "Ohne AO bleibt die 128er-Wand in vier Greedy-Runs");
        assertEquals(64, countVerticalQuads(withAo.opaqueData(), withAo.yBase(),
                64F, 60F, 61F),
                "Die weiche Kontaktkante muss aus 64 sichtbaren 2x1-L1-Zellen bestehen");
        assertEquals(4, countVerticalQuads(withAo.opaqueData(), withAo.yBase(),
                64F, 61F, 80F),
                "Der uniforme Wandrest muss weiterhin in vier 32er-Runs mergen");
        assertEquals(0, countVerticalQuads(withAo.opaqueData(), withAo.yBase(),
                64F, 60F, 80F),
                "AO darf nicht als ein hoher Gradient ueber die komplette Wand laufen");

        assertEquals(List.of(0x5C5C5C, 0x5C5C5C, 0x999999, 0x999999),
                colorsOfVerticalQuad(withAo.opaqueData(), withAo.yBase(),
                        64F, 60F, 61F),
                "Die 2x1-L1-Wandzelle muss dieselben Corner-AO-Stufen wie L0 tragen");

        List<Integer> sharedEdge = colorsOfVerticalVertex(withAo.opaqueData(), withAo.yBase(),
                64F, 61F, 64F);
        assertFalse(sharedEdge.isEmpty());
        assertEquals(1, sharedEdge.stream().distinct().count(),
                "Kontaktzelle und heller Wandrest muessen an ihrer gemeinsamen Kante nahtlos sein");
    }

    @Test
    void levelOneOddHeightKeepsOneBlockVerticalAoCells() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 66 : 61));
        LodManager.LodMeshResult withoutAo = mesh(source, false, 1);
        LodManager.LodMeshResult withAo = mesh(source, true, 1);

        assertEquals(4, countVerticalQuads(withoutAo.opaqueData(), withoutAo.yBase(),
                64F, 61F, 66F));
        assertEquals(64, countVerticalQuads(withAo.opaqueData(), withAo.yBase(),
                64F, 61F, 62F),
                "Die untere Kontaktkante muss genau eine Blockzeile hoch bleiben");
        assertEquals(4, countVerticalQuads(withAo.opaqueData(), withAo.yBase(),
                64F, 62F, 66F),
                "Uniforme Ein-Block-Zeilen sollen wieder zu hohen Greedy-Quads mergen");
        assertEquals(0, countVerticalQuads(withAo.opaqueData(), withAo.yBase(),
                64F, 62F, 63F));
        assertEquals(0, countVerticalQuads(withAo.opaqueData(), withAo.yBase(),
                64F, 63F, 64F),
                "Innerhalb des uniformen Wandrests darf kein einzelnes 2x1-Quad bleiben");
    }

    @Test
    void levelOneAoKeepsEveryQuadInsideItsMeshingBounds() {
        LodManager.LodMeshResult result = mesh(
                columns((x, z) -> terrain(x < 64 ? 112 : 48)), true, 1);

        assertValidQuadBounds(result.opaqueData(), result.yBase());
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
        return mesh(source, ao, level, neighbors, LodManager.LodClipSnapshot.centerOnly(0));
    }

    private static LodManager.LodMeshResult mesh(LodDataSource source, boolean ao, int level,
                                                  LodManager.LodNeighborSnapshot neighbors,
                                                  LodManager.LodClipSnapshot clipSnapshot) {
        GameSettings settings = GameSettings.get();
        boolean previousAo = settings.ambientOcclusion;
        GameSettings.LodQuality previousQuality = settings.lodQuality;
        /* Diese Tests pruefen den AO-MODUS je Level. Der haengt seit LodQuality an einer
           Einstellung, die GameSettings.get() aus der echten options.json des Nutzers laedt —
           ohne dieses Pinnen faellt der Test um, sobald jemand im Spiel MID/HIGH waehlt. */
        settings.lodQuality = GameSettings.LodQuality.LOW;
        settings.ambientOcclusion = ao;
        try {
            return new LodMesher().mesh(source, new LodBlockAppearance(), LodConfig.of(16, 128),
                    level, 1, 0, 0, 0, clipSnapshot,
                    neighbors, 64, 64);
        } finally {
            settings.ambientOcclusion = previousAo;
            settings.lodQuality = previousQuality;
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

    private static List<Integer> colorsOfVerticalVertex(int[] data, int yBase,
                                                         float expectedX, float expectedY,
                                                         float expectedZ) {
        List<Integer> result = new ArrayList<>();
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean constantX = true;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                constantX &= close(xzCoordinate(data[p] & 0xFFFF), expectedX);
            }
            if (!constantX) continue;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float x = xzCoordinate(data[p] & 0xFFFF);
                float y = yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                float z = xzCoordinate(data[p + 1] & 0xFFFF);
                if (close(x, expectedX) && close(y, expectedY) && close(z, expectedZ)) {
                    result.add(data[p + 3] & 0xFFFFFF);
                }
            }
        }
        return result;
    }

    private static void assertValidQuadBounds(int[] data, int yBase) {
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float x = xzCoordinate(data[p] & 0xFFFF);
                float y = yCoordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                float z = xzCoordinate(data[p + 1] & 0xFFFF);
                assertTrue(x >= 0F && x <= 128F && z >= 0F && z <= 128F,
                        "AO darf keine X/Z-Position ausserhalb der Region erzeugen");
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            float width = Math.max(maxX - minX, maxZ - minZ);
            float height = maxY - minY;
            assertTrue(width <= 32.01F && height <= 32.01F,
                    "Ein AO-Quad darf die Pack-/Greedy-Grenze nicht ueberschreiten");
            assertTrue(width > 0.01F || height > 0.01F, "Degeneriertes AO-Quad gefunden");
        }
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

    private static LodDataSource fineReliefWithL1Columns(ColumnFactory levelOne) {
        return new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                if (size == 1) {
                    int fineTop = 64 + (Math.floorDiv(x, 2) + Math.floorDiv(z, 2) & 1);
                    return terrain(fineTop);
                }
                return levelOne.at(x, z);
            }
            @Override public long sampleSurface(int x, int z, int size) {
                LodColumn column = this.sampleColumn(x, z, size);
                long top = column.interval(column.size() - 1);
                return LodDataSource.pack(LodColumn.state(top), LodColumn.maxY(top) - 1);
            }
        };
    }

    @FunctionalInterface
    private interface ColumnFactory {
        LodColumn at(int x, int z);
    }
}
