package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.model.CompactCompositeMaterialTable;

/**
 * Bitlayout des kompakten, achsenparallelen Full-Cube-Pfads.
 *
 * <p>Die Geometrie besteht immer aus zwei {@code uint}s (8 Byte). Optional folgt in einem
 * getrennten Stream entweder ein uniformes Shading-{@code uint} oder vier Corner-{@code uint}s.
 * Licht wird als Summe der vier 0..15-Samples gespeichert (0..60); damit kann der Shader den
 * bisherigen 8-Bit-Mittelwert exakt wiederherstellen und das Layout ist bereits RGB-faehig.</p>
 */
public final class PackedTerrainQuad {

    public static final int GEOMETRY_INTS = 2;
    public static final int UNIFORM_SHADING_INTS = 1;
    public static final int CORNER_SHADING_INTS = 4;

    public static final int SHADING_STANDARD = 0;
    public static final int SHADING_UNIFORM = 1;
    public static final int SHADING_CORNER = 2;

    private static final int MASK_5 = 0x1F;
    private static final int MASK_6 = 0x3F;

    public static int geometry0(int x, int y, int z, int axis, boolean positive,
                                int width, int height, int uvTransform, boolean diagonalFlip) {
        requireRange("x", x, 0, 31);
        requireRange("y", y, 0, 31);
        requireRange("z", z, 0, 31);
        requireRange("axis", axis, 0, 2);
        requireRange("width", width, 1, 32);
        requireRange("height", height, 1, 32);
        requireRange("uvTransform", uvTransform, 0, 7);
        return x | (y << 5) | (z << 10) | (axis << 15) | (positive ? 1 << 17 : 0)
                | ((width - 1) << 18) | ((height - 1) << 23) | (uvTransform << 28)
                | (diagonalFlip ? 1 << 31 : 0);
    }

    public static int geometry1(int materialId, int tintIndex, int flags) {
        requireRange("materialId", materialId, 0, 0xFFFF);
        requireRange("tintIndex", tintIndex, 0, 0xFF);
        requireRange("flags", flags, 0, 0xFF);
        return materialId | (tintIndex << 16) | (flags << 24);
    }

    public static int x(int word) { return word & MASK_5; }
    public static int y(int word) { return (word >>> 5) & MASK_5; }
    public static int z(int word) { return (word >>> 10) & MASK_5; }
    public static int axis(int word) { return (word >>> 15) & 3; }
    public static boolean positive(int word) { return ((word >>> 17) & 1) != 0; }
    public static int width(int word) { return ((word >>> 18) & MASK_5) + 1; }
    public static int height(int word) { return ((word >>> 23) & MASK_5) + 1; }
    public static int uvTransform(int word) { return (word >>> 28) & 7; }
    public static boolean diagonalFlip(int word) { return word < 0; }
    /** Direct texture layer or a high-bit-tagged compact composite table handle. */
    public static int materialId(int word) { return word & 0xFFFF; }
    public static boolean compositeMaterial(int word) {
        return CompactCompositeMaterialTable.isComposite(materialId(word));
    }
    public static int tintIndex(int word) { return (word >>> 16) & 0xFF; }
    public static int flags(int word) { return word >>> 24; }

    /** Sky/R/G/B je sechs Bit, AO zwei Bit. Die oberen sechs Bit bleiben reserviert. */
    public static int uniformShading(int sky, int red, int green, int blue, int ao) {
        return shadingWord(sky, red, green, blue, ao, 0);
    }

    /**
     * Ein Corner-Wort. Die oberen sechs Bit tragen je Ecke ein Viertel der 24-Bit-Festfarbe;
     * vier Corner-Worte enthalten zusammen damit exakt die geplanten 128 Bit.
     */
    public static int cornerShading(int sky, int red, int green, int blue, int ao,
                                    int fixedTint, int corner) {
        requireRange("corner", corner, 0, 3);
        int tintPart = (fixedTint >>> (corner * 6)) & MASK_6;
        return shadingWord(sky, red, green, blue, ao, tintPart);
    }

    private static int shadingWord(int sky, int red, int green, int blue, int ao, int upper) {
        requireRange("sky", sky, 0, 60);
        requireRange("red", red, 0, 60);
        requireRange("green", green, 0, 60);
        requireRange("blue", blue, 0, 60);
        requireRange("ao", ao, 0, 3);
        return sky | (red << 6) | (green << 12) | (blue << 18) | (ao << 24) | (upper << 26);
    }

    public static int skySum(int word) { return word & MASK_6; }
    public static int redSum(int word) { return (word >>> 6) & MASK_6; }
    public static int greenSum(int word) { return (word >>> 12) & MASK_6; }
    public static int blueSum(int word) { return (word >>> 18) & MASK_6; }
    public static int ao(int word) { return (word >>> 24) & 3; }

    public static int fixedTint(int c0, int c1, int c2, int c3) {
        return ((c0 >>> 26) & MASK_6) | (((c1 >>> 26) & MASK_6) << 6)
                | (((c2 >>> 26) & MASK_6) << 12) | (((c3 >>> 26) & MASK_6) << 18);
    }

    /** Kehrt den bisherigen Byte-Mittelwert eindeutig zur 0..60-Summe um. */
    public static int byteLightToSampleSum(int value) {
        requireRange("value", value, 0, 255);
        return Math.clamp((value * 4 + 8) / 17, 0, 60);
    }

    /** Rekonstruiert exakt VertexLight.average(sum, *, 4) fuer einen Kanal. */
    public static int sampleSumToByteLight(int sum) {
        requireRange("sum", sum, 0, 60);
        return (sum * 17 + 2) / 4;
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " ausserhalb " + min + ".." + max + ": " + value);
        }
    }

    private PackedTerrainQuad() {}
}
