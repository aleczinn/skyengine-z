package de.skyengine.game.world.chunk;

/**
 * Kompaktes, achsenparalleles Quad fuer Vertex-Pulling. Der Basisdatensatz ist exakt ein
 * {@code long} (8 Bytes); optional folgt ein {@link Shading}-Datensatz mit 16 Bytes.
 *
 * <pre>
 * geometry  0..31: x5 y5 z5 axis2 side1 width5 height5 uv3 diagonal1
 * material 32..47: Materialtabellen-Index
 * tint     48..55: optionaler Tint-/Paletten-Index
 * flags    56..63: Renderflags
 * </pre>
 *
 * Positionen sind immer section-lokal (0..31), niemals Weltkoordinaten. Der Draw-Descriptor
 * liefert den kamerarelativ berechneten Section-Ursprung.
 */
public final class PackedQuad {

    public static final int BASE_BYTES = Long.BYTES;
    public static final int SHADED_BYTES = BASE_BYTES + 2 * Long.BYTES;

    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    private static final int X_SHIFT = 0;
    private static final int Y_SHIFT = 5;
    private static final int Z_SHIFT = 10;
    private static final int AXIS_SHIFT = 15;
    private static final int SIDE_SHIFT = 17;
    private static final int WIDTH_SHIFT = 18;
    private static final int HEIGHT_SHIFT = 23;
    private static final int UV_SHIFT = 28;
    private static final int DIAGONAL_SHIFT = 31;
    private static final int MATERIAL_SHIFT = 32;
    private static final int TINT_SHIFT = 48;
    private static final int FLAGS_SHIFT = 56;

    public static long pack(int x, int y, int z, int axis, boolean positiveSide,
                            int width, int height, int uvTransform, boolean flippedDiagonal,
                            int material, int tintIndex, int flags) {
        requireRange("x", x, 0, 31);
        requireRange("y", y, 0, 31);
        requireRange("z", z, 0, 31);
        requireRange("axis", axis, AXIS_X, AXIS_Z);
        requireRange("width", width, 1, 32);
        requireRange("height", height, 1, 32);
        requireRange("uvTransform", uvTransform, 0, 7);
        requireRange("material", material, 0, 0xFFFF);
        requireRange("tintIndex", tintIndex, 0, 0xFF);
        requireRange("flags", flags, 0, 0xFF);
        return (long) x << X_SHIFT
                | (long) y << Y_SHIFT
                | (long) z << Z_SHIFT
                | (long) axis << AXIS_SHIFT
                | (positiveSide ? 1L : 0L) << SIDE_SHIFT
                | (long) (width - 1) << WIDTH_SHIFT
                | (long) (height - 1) << HEIGHT_SHIFT
                | (long) uvTransform << UV_SHIFT
                | (flippedDiagonal ? 1L : 0L) << DIAGONAL_SHIFT
                | (long) material << MATERIAL_SHIFT
                | (long) tintIndex << TINT_SHIFT
                | (long) flags << FLAGS_SHIFT;
    }

    public static int x(long packed) { return (int) (packed & 31L); }
    public static int y(long packed) { return (int) (packed >>> Y_SHIFT & 31L); }
    public static int z(long packed) { return (int) (packed >>> Z_SHIFT & 31L); }
    public static int axis(long packed) { return (int) (packed >>> AXIS_SHIFT & 3L); }
    public static boolean positiveSide(long packed) { return (packed & 1L << SIDE_SHIFT) != 0; }
    public static int width(long packed) { return (int) (packed >>> WIDTH_SHIFT & 31L) + 1; }
    public static int height(long packed) { return (int) (packed >>> HEIGHT_SHIFT & 31L) + 1; }
    public static int uvTransform(long packed) { return (int) (packed >>> UV_SHIFT & 7L); }
    public static boolean flippedDiagonal(long packed) { return (packed & 1L << DIAGONAL_SHIFT) != 0; }
    public static int material(long packed) { return (int) (packed >>> MATERIAL_SHIFT & 0xFFFFL); }
    public static int tintIndex(long packed) { return (int) (packed >>> TINT_SHIFT & 0xFFL); }
    public static int flags(long packed) { return (int) (packed >>> FLAGS_SHIFT & 0xFFL); }

    /** Eine vom Vertex-Shader rekonstruierte Ecke; dient auch CPU-Tests und Debug-Exporten. */
    public record Vertex(float x, float y, float z, float u, float v) {}

    /**
     * Rekonstruiert eine der vier Ecken exakt wie der Vertex-Pulling-Shader. Die gespeicherte
     * Normalenkoordinate bezeichnet die Zelle; auf der positiven Seite wird die Face-Ebene um
     * eine Zelle verschoben. Dadurch bleibt auch die aeussere Ebene einer Section mit
     * Koordinate 32 darstellbar, obwohl x/y/z jeweils nur fuenf Bit besitzen.
     */
    public static Vertex vertex(long packed, int corner) {
        requireRange("corner", corner, 0, 3);
        int axis = axis(packed);
        if (axis > AXIS_Z) throw new IllegalArgumentException("Reservierte Achse: " + axis);
        int width = width(packed), height = height(packed);
        boolean basePositive = axis != AXIS_Y; // cross(T1,T2): X=+, Y=-, Z=+
        boolean forward = positiveSide(packed) == basePositive;
        int diagonalCorner = flippedDiagonal(packed) ? (corner + 1) & 3 : corner;
        int orderedCorner = forward ? diagonalCorner
                : (diagonalCorner == 0 ? 0 : 4 - diagonalCorner);
        int alongWidth = orderedCorner == 1 || orderedCorner == 2 ? 1 : 0;
        int alongHeight = orderedCorner >= 2 ? 1 : 0;

        float px = x(packed), py = y(packed), pz = z(packed);
        if (positiveSide(packed)) {
            if (axis == AXIS_X) px++;
            else if (axis == AXIS_Y) py++;
            else pz++;
        }
        if (axis == AXIS_X) {
            py += alongWidth * width;
            pz += alongHeight * height;
        } else if (axis == AXIS_Y) {
            px += alongWidth * width;
            pz += alongHeight * height;
        } else {
            px += alongWidth * width;
            py += alongHeight * height;
        }

        float su = alongWidth, sv = alongHeight;
        int transform = uvTransform(packed);
        if ((transform & 4) != 0) su = 1F - su;
        float tu, tv;
        switch (transform & 3) {
            case 1 -> { tu = sv; tv = 1F - su; }
            case 2 -> { tu = 1F - su; tv = 1F - sv; }
            case 3 -> { tu = 1F - sv; tv = su; }
            default -> { tu = su; tv = sv; }
        }
        boolean swapped = (transform & 1) != 0;
        float u = tu * (swapped ? height : width);
        float v = tv * (swapped ? width : height);
        return new Vertex(px, py, pz, u, v);
    }

    /**
     * 128-Bit-Shadingblock. Vier Ecken tragen je Sky/R/G/B als rohe 6-Bit-Summe 0..60;
     * dadurch bleibt das bisherige Mittel aus vier 0..15-Lichtsamples verlustfrei. Danach
     * folgen vier AO-Werte (je 2 Bit) und ein RGB8-Tint.
     */
    public record Shading(long low, long high) {
        private static final int CHANNELS = 16;
        private static final int CHANNEL_BITS = 6;
        private static final int AO_OFFSET = CHANNELS * CHANNEL_BITS;
        private static final int TINT_OFFSET = AO_OFFSET + 8;

        public static Shading pack(int[][] cornerLightSums, int[] cornerAo, int tintRgb) {
            if (cornerLightSums == null || cornerLightSums.length != 4
                    || cornerAo == null || cornerAo.length != 4) {
                throw new IllegalArgumentException("Vier Licht- und AO-Ecken erforderlich");
            }
            long low = 0L, high = 0L;
            for (int corner = 0; corner < 4; corner++) {
                if (cornerLightSums[corner] == null || cornerLightSums[corner].length != 4) {
                    throw new IllegalArgumentException("Jede Ecke braucht Sky/R/G/B");
                }
                for (int channel = 0; channel < 4; channel++) {
                    int value = cornerLightSums[corner][channel];
                    requireRange("lightSum", value, 0, 60);
                    long[] words = put(low, high, (corner * 4 + channel) * CHANNEL_BITS,
                            CHANNEL_BITS, value);
                    low = words[0]; high = words[1];
                }
                requireRange("ao", cornerAo[corner], 0, 3);
                high |= (long) cornerAo[corner] << (AO_OFFSET - 64 + corner * 2);
            }
            requireRange("tintRgb", tintRgb, 0, 0xFFFFFF);
            high |= (long) tintRgb << (TINT_OFFSET - 64);
            return new Shading(low, high);
        }

        /** channel: 0=Sky, 1=Rot, 2=Gruen, 3=Blau. */
        public int lightSum(int corner, int channel) {
            requireRange("corner", corner, 0, 3);
            requireRange("channel", channel, 0, 3);
            return (int) get(this.low, this.high, (corner * 4 + channel) * CHANNEL_BITS,
                    CHANNEL_BITS);
        }

        public int ao(int corner) {
            requireRange("corner", corner, 0, 3);
            return (int) (this.high >>> (AO_OFFSET - 64 + corner * 2) & 3L);
        }

        public int tintRgb() { return (int) (this.high >>> (TINT_OFFSET - 64) & 0xFFFFFFL); }

        private static long[] put(long low, long high, int offset, int bits, long value) {
            if (offset >= 64) return new long[]{low, high | value << (offset - 64)};
            int inLow = Math.min(bits, 64 - offset);
            low |= (value & ((1L << inLow) - 1L)) << offset;
            if (inLow < bits) high |= value >>> inLow;
            return new long[]{low, high};
        }

        private static long get(long low, long high, int offset, int bits) {
            long mask = (1L << bits) - 1L;
            if (offset >= 64) return high >>> (offset - 64) & mask;
            if (offset + bits <= 64) return low >>> offset & mask;
            return (low >>> offset | high << (64 - offset)) & mask;
        }
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " ausserhalb " + min + ".." + max + ": " + value);
        }
    }

    private PackedQuad() {}
}
