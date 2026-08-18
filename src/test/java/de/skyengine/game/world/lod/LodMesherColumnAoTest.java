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
        List<Integer> colors = targetTopColors(steppedColumns(), true);

        assertEquals(List.of(0x666666, 0xCCCCCC, 0xFFFFFF, 0xCCCCCC), colors);
    }

    @Test
    void disabledAoKeepsColumnTerrainFullyBright() {
        LodManager.LodMeshResult result = mesh(steppedColumns(), false);
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
                targetTopColors(source, true));
    }

    @Test
    void grassCliffEmitsOnlyBaseWallsWithoutLodOverlay() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 64 : 62, Blocks.GRASS_BLOCK));
        LodManager.LodMeshResult result = mesh(source, true);

        assertEquals(4, countVerticalQuads(result.opaqueData(), result.yBase(), 64F, 62F, 64F),
                "Die 128 Blöcke lange Kante besteht aus vier 32er-Runs ohne Overlay-Duplikate");
    }

    @Test
    void columnPathBakesContactAoIntoVerticalWalls() {
        LodDataSource source = columns((x, z) -> terrain(x < 64 ? 64 : 62));
        LodManager.LodMeshResult result = mesh(source, true);

        List<Integer> colors = colorsOfVerticalQuad(result.opaqueData(), result.yBase(),
                64F, 62F, 64F);
        assertFalse(colors.isEmpty());
        int bottom = brightness(colors.get(0)) + brightness(colors.get(1));
        int top = brightness(colors.get(2)) + brightness(colors.get(3));
        assertTrue(bottom < top, "Die Kontaktkante zum tieferen Terrain muss dunkler als die Wandoberkante sein");
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

    private static LodDataSource steppedColumns() {
        return columns((x, z) -> terrain((x == 62 && z == 64)
                || (x == 64 && z == 62) ? 65 : 64));
    }

    private static List<Integer> targetTopColors(LodDataSource source, boolean ao) {
        LodManager.LodMeshResult result = mesh(source, ao);
        List<Integer> colors = colorsOfHorizontalQuad(result.opaqueData(), result.yBase(),
                TARGET_X, TARGET_Z, TARGET_X + 2F, TARGET_Z + 2F, TARGET_TOP);
        assertFalse(colors.isEmpty(), "Die AO-Zielzelle darf nicht in ein inkompatibles Greedy-Quad aufgehen");
        return colors;
    }

    private static LodManager.LodMeshResult mesh(LodDataSource source, boolean ao) {
        GameSettings settings = GameSettings.get();
        boolean previousAo = settings.ambientOcclusion;
        settings.ambientOcclusion = ao;
        try {
            return new LodMesher().mesh(source, new LodBlockAppearance(), LodConfig.of(16, 128),
                    1, 1, 0, 0, 0, 0, 64, 64);
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
                float vx = coordinate(data[p] & 0xFFFF);
                float vy = coordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                float vz = coordinate(data[p + 1] & 0xFFFF);
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
                horizontal &= close(coordinate((data[p] >>> 16) & 0xFFFF) + yBase, y);
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
                constantX &= close(coordinate(data[p] & 0xFFFF), expectedX);
                float y = coordinate((data[p] >>> 16) & 0xFFFF) + yBase;
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
                constantX &= close(coordinate(data[p] & 0xFFFF), expectedX);
                float y = coordinate((data[p] >>> 16) & 0xFFFF) + yBase;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                colors.add(data[p + 3] & 0xFFFFFF);
            }
            if (constantX && close(minY, expectedMinY) && close(maxY, expectedMaxY)) return colors;
        }
        return List.of();
    }

    private static int brightness(int color) {
        return (color >> 16 & 0xFF) + (color >> 8 & 0xFF) + (color & 0xFF);
    }

    private static float coordinate(int packed) {
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

    @FunctionalInterface
    private interface ColumnFactory {
        LodColumn at(int x, int z);
    }
}
