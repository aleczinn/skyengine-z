package de.skyengine.game.world.chunk;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChunkMesherCornerCompatibilityTest {

    @Test
    void constantAndLinearFieldsMatchReferenceAtLargeSizes() {
        assertField(0, 32, 32, (x, y, corner) -> VertexLight.fromLevels(15, 0),
                (x, y, corner) -> (byte) 3, (x, y) -> (byte) ((x + y) & 1));
        assertField(0, 8, 8,
                (x, y, corner) -> {
                    int[] s = {0, 0, 1, 1};
                    int[] t = {0, 1, 1, 0};
                    int px = x + s[corner], py = y + t[corner];
                    return VertexLight.fromLevels(px + py, px);
                },
                (x, y, corner) -> (byte) 2,
                (x, y) -> (byte) ((x ^ y) & 1));
    }

    @Test
    void isolatedAoSkyAndBlockDifferencesMatchReference() {
        int width = 12, height = 9;
        int[] lights = constantLights(width, height, VertexLight.fromLevels(12, 3));
        byte[] ao = constantAo(width, height, (byte) 3);
        byte[] diagonals = new byte[width * height];
        assertComparison(0, width, height, lights, ao, diagonals);

        ao[((4 * width + 5) << 2) | 2] = 1;
        assertComparison(0, width, height, lights, ao, diagonals);
        ao[((4 * width + 5) << 2) | 2] = 3;

        lights[((3 * width + 7) << 2) | 1] = VertexLight.fromLevels(5, 3);
        assertComparison(0, width, height, lights, ao, diagonals);
        lights[((3 * width + 7) << 2) | 1] = VertexLight.fromLevels(12, 9);
        assertComparison(0, width, height, lights, ao, diagonals);
    }

    @Test
    void deterministicQuantizedFieldsMatchReferenceForAllFaces() {
        Random random = new Random(0x5EED_C0DEL);
        for (int face = 0; face < 6; face++) {
            for (int iteration = 0; iteration < 300; iteration++) {
                int width = 1 + random.nextInt(12);
                int height = 1 + random.nextInt(12);
                int cells = width * height;
                int[] lights = new int[cells * 4];
                byte[] ao = new byte[cells * 4];
                byte[] diagonals = new byte[cells];
                for (int i = 0; i < cells; i++) {
                    diagonals[i] = (byte) random.nextInt(2);
                    for (int corner = 0; corner < 4; corner++) {
                        lights[(i << 2) | corner] = VertexLight.fromLevels(
                                random.nextInt(16), random.nextInt(16));
                        ao[(i << 2) | corner] = (byte) random.nextInt(4);
                    }
                }
                assertComparison(face, width, height, lights, ao, diagonals);
            }
        }
    }

    private static void assertField(int face, int width, int height, LightField lightField,
                                    AoField aoField, DiagonalField diagonalField) {
        int[] lights = new int[width * height * 4];
        byte[] ao = new byte[lights.length];
        byte[] diagonals = new byte[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int cell = y * width + x;
            diagonals[cell] = diagonalField.value(x, y);
            for (int corner = 0; corner < 4; corner++) {
                lights[(cell << 2) | corner] = lightField.value(x, y, corner);
                ao[(cell << 2) | corner] = aoField.value(x, y, corner);
            }
        }
        assertComparison(face, width, height, lights, ao, diagonals);
    }

    private static void assertComparison(int face, int width, int height, int[] lights,
                                         byte[] ao, byte[] diagonals) {
        int[] result = ChunkMesher.compareCornerCompatibilityForTest(
                face, width, height, lights, ao, diagonals);
        assertEquals(result[0], result[1],
                "Reference/optimized mismatch face=" + face + " size=" + width + 'x' + height);
    }

    private static int[] constantLights(int width, int height, int value) {
        int[] result = new int[width * height * 4];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static byte[] constantAo(int width, int height, byte value) {
        byte[] result = new byte[width * height * 4];
        java.util.Arrays.fill(result, value);
        return result;
    }

    @FunctionalInterface private interface LightField { int value(int x, int y, int corner); }
    @FunctionalInterface private interface AoField { byte value(int x, int y, int corner); }
    @FunctionalInterface private interface DiagonalField { byte value(int x, int y); }
}
