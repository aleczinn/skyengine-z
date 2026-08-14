package de.skyengine.graphics.world;

/**
 * Exakt als float darstellbare Draw-Metadaten im bisherigen {@code DrawOffsets.w}. Dadurch
 * bleibt der Offset-SSBO bei einem {@code vec4}: Level, 16-Bit-Konfliktmaske und die
 * Positionsskalen-Code brauchen zusammen nur 23 Integer-Bits.
 */
final class DrawMetadata {

    static final int SECTION_SCALE_CODE = 0;  // 1/ChunkMesher.POS_SCALE = 1/1024
    static final int LOD_REGION_SCALE_CODE = 1; // 1/127
    static final int LOD_SUPER_SCALE_CODE = 2;  // 1/64

    private static final int LEVEL_BITS = 3;
    private static final int CONFLICT_BITS = 16;
    private static final int CONFLICT_SHIFT = LEVEL_BITS;
    private static final int SCALE_CODE_SHIFT = CONFLICT_SHIFT + CONFLICT_BITS;
    private static final int LEVEL_MASK = (1 << LEVEL_BITS) - 1;
    private static final int CONFLICT_MASK = (1 << CONFLICT_BITS) - 1;
    private static final int SCALE_MASK = 0xF;

    private DrawMetadata() {}

    static float pack(int level, int positionScaleCode, int conflictMask) {
        if ((level & ~LEVEL_MASK) != 0) throw new IllegalArgumentException("LOD-Level ausserhalb 0..7: " + level);
        if ((positionScaleCode & ~SCALE_MASK) != 0) {
            throw new IllegalArgumentException("Positionsskalen-Code ausserhalb 0..15: " + positionScaleCode);
        }
        int packed = level
                | (conflictMask & CONFLICT_MASK) << CONFLICT_SHIFT
                | positionScaleCode << SCALE_CODE_SHIFT;
        return packed;
    }

    static int level(float packed) {
        return Math.round(packed) & LEVEL_MASK;
    }

    static int conflictMask(float packed) {
        return Math.round(packed) >>> CONFLICT_SHIFT & CONFLICT_MASK;
    }

    static int positionScaleCode(float packed) {
        return Math.round(packed) >>> SCALE_CODE_SHIFT & SCALE_MASK;
    }
}
