package de.skyengine.game.world.generator;

/** Compact terrain-generator sample containing a block-state id and its surface height. */
public final class SurfaceSample {
    private SurfaceSample() {}

    public static long pack(int blockState, int height) {
        return (Integer.toUnsignedLong(blockState) << 32) | Integer.toUnsignedLong(height);
    }

    public static int block(long sample) {
        return (int) (sample >>> 32);
    }

    public static int height(long sample) {
        return (int) sample;
    }
}
