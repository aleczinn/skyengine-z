package de.skyengine.game.world.lod;

/** Kompakte kanonische Zelle der volumetrischen LOD-Hierarchie. */
public final class LodVoxel {

    public static final int PROVENANCE_ANALYTIC = 0;
    public static final int PROVENANCE_GENERATED = 1;
    public static final int PROVENANCE_SAVED = 2;
    public static final int PROVENANCE_LIVE = 3;

    public static long pack(int stateId, int sky, int red, int green, int blue,
                            int coverage, int provenance, int importance) {
        range("stateId", stateId, 0, Integer.MAX_VALUE);
        range("sky", sky, 0, 15);
        range("red", red, 0, 15);
        range("green", green, 0, 15);
        range("blue", blue, 0, 15);
        range("coverage", coverage, 0, 255);
        range("provenance", provenance, 0, 3);
        range("importance", importance, 0, 63);
        int light = sky | red << 4 | green << 8 | blue << 12;
        int metadata = provenance | importance << 2;
        return Integer.toUnsignedLong(stateId) | (long) light << 32
                | (long) coverage << 48 | (long) metadata << 56;
    }

    public static int stateId(long voxel) { return (int) voxel; }
    public static int sky(long voxel) { return (int) (voxel >>> 32 & 15L); }
    public static int red(long voxel) { return (int) (voxel >>> 36 & 15L); }
    public static int green(long voxel) { return (int) (voxel >>> 40 & 15L); }
    public static int blue(long voxel) { return (int) (voxel >>> 44 & 15L); }
    public static int coverage(long voxel) { return (int) (voxel >>> 48 & 255L); }
    public static int provenance(long voxel) { return (int) (voxel >>> 56 & 3L); }
    public static int importance(long voxel) { return (int) (voxel >>> 58 & 63L); }
    public static boolean isEmpty(long voxel) { return coverage(voxel) == 0; }

    private static void range(String name, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + ": " + value);
    }

    private LodVoxel() {}
}
