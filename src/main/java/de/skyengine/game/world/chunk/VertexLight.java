package de.skyengine.game.world.chunk;

/**
 * Gemeinsames Lichtlayout des fuenften Chunk-Vertex-Ints. Die Simulation bleibt bei zwei
 * Nibbles mit Leveln 0..15; erst fuer das Smooth Lighting werden die gemittelten Eckwerte auf
 * je ein Byte skaliert. Dadurch bleiben auch halbe, Drittel- und Viertelstufen erhalten.
 */
public final class VertexLight {

    public static final int CHANNEL_MAX = 255;
    public static final int SKY_MASK = 0xFF;
    public static final int BLOCK_SHIFT = 8;
    public static final int BLOCK_MASK = 0xFF << BLOCK_SHIFT;
    public static final int CHANNELS_MASK = SKY_MASK | BLOCK_MASK;
    /** Im generischen GPU-Vertexformat liegen Flags nach vier 6-Bit-Lichtkanaelen. */
    public static final int FIRST_FLAG_BIT = 24;
    public static final int GENERIC_CHANNELS_MASK = 0x00FFFFFF;
    public static final int GENERIC_FLAGS_MASK = 0xFF000000;

    /** Wandelt die beiden gespeicherten Level 0..15 in das praezise Vertexformat um. */
    public static int fromLevels(int sky, int block) {
        return levelToByte(sky) | (levelToByte(block) << BLOCK_SHIFT);
    }

    /** Wandelt das interne Nibble-Layout (Himmel 0..3, Block 4..7) ins Vertexformat um. */
    public static int fromStoragePacked(int packed) {
        return fromLevels(packed & 0xF, (packed >>> 4) & 0xF);
    }

    /** Mittelt aufsummierte 0..15-Samples ohne Rueckrundung auf eine ganze Lichtstufe. */
    public static int average(int skySum, int blockSum, int count) {
        if (count <= 0) return 0;
        return averageChannel(skySum, count) | (averageChannel(blockSum, count) << BLOCK_SHIFT);
    }

    public static int sky(int packed) {
        return packed & SKY_MASK;
    }

    public static int block(int packed) {
        return (packed >>> BLOCK_SHIFT) & SKY_MASK;
    }

    /** Derselbe monochrome Max-Vergleich wie im Fragment-Shader. */
    public static int effective(int packed) {
        return Math.max(sky(packed), block(packed));
    }

    /**
     * Migriert das interne Sky/Mono-Block-Byteformat in Sky/R/G/B je sechs Bit. Die Werte sind
     * weiterhin die verlustfreien Summen der vier 0..15-Corner-Samples; Blocklicht wird bis zur
     * RGB-Lichtsimulation identisch in R/G/B repliziert. Bits 24..31 bleiben Renderer-Flags.
     */
    public static int packGenericRgb(int packed) {
        int sky = PackedTerrainQuad.byteLightToSampleSum(sky(packed));
        int block = PackedTerrainQuad.byteLightToSampleSum(block(packed));
        return sky | block << 6 | block << 12 | block << 18 | packed & GENERIC_FLAGS_MASK;
    }

    public static int genericSky(int packed) { return genericChannel(packed, 0); }
    public static int genericRed(int packed) { return genericChannel(packed, 6); }
    public static int genericGreen(int packed) { return genericChannel(packed, 12); }
    public static int genericBlue(int packed) { return genericChannel(packed, 18); }

    private static int genericChannel(int packed, int shift) {
        return PackedTerrainQuad.sampleSumToByteLight((packed >>> shift) & 0x3F);
    }

    public static int levelToByte(int level) {
        return Math.clamp(level, 0, 15) * 17;
    }

    private static int averageChannel(int sum, int count) {
        return Math.clamp((sum * 17 + count / 2) / count, 0, CHANNEL_MAX);
    }

    private VertexLight() {}
}
