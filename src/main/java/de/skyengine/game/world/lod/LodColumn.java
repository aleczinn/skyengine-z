package de.skyengine.game.world.lod;

import java.util.Arrays;

/**
 * Kanonische, vertikale LOD-Spalte. Ein Intervall umfasst ganze Blockzellen in
 * {@code [minY,maxY)}. Die Reihenfolge ist immer von unten nach oben.
 */
public final class LodColumn {

    public static final int MAX_INTERVALS = 4;
    public static final int FLAG_LANDMARK = 1;
    public static final int FLAG_SKY_OPEN = 2;
    private static final int FLAG_MASK = FLAG_LANDMARK | FLAG_SKY_OPEN;
    private static final int COVERAGE_SHIFT = 2;
    private static final int COVERAGE_MASK = 0x3FF;
    public static final LodColumn EMPTY = new LodColumn(new long[0]);

    private final long[] intervals;

    public LodColumn(long[] intervals) {
        if (intervals.length > MAX_INTERVALS) {
            throw new IllegalArgumentException("Zu viele LOD-Intervalle: " + intervals.length);
        }
        this.intervals = intervals.length == 0 ? intervals : Arrays.copyOf(intervals, intervals.length);
    }

    public int size() {
        return this.intervals.length;
    }

    public long interval(int index) {
        return this.intervals[index];
    }

    public long[] copyIntervals() {
        return Arrays.copyOf(this.intervals, this.intervals.length);
    }

    public static long pack(int stateId, int minY, int maxY, int flags) {
        return pack(stateId, minY, maxY, flags, 1);
    }

    public static long pack(int stateId, int minY, int maxY, int flags, int coverage) {
        if (minY < 0 || minY > 511 || maxY < 1 || maxY > 512 || minY >= maxY) {
            throw new IllegalArgumentException("Ungültiges LOD-Intervall " + minY + ".." + maxY);
        }
        if (coverage < 1 || coverage > 1024) {
            throw new IllegalArgumentException("Ungültige LOD-Abdeckung: " + coverage);
        }
        int packedFlags = (flags & FLAG_MASK) | ((coverage - 1) << COVERAGE_SHIFT);
        return (stateId & 0xFFFFFFFFL)
                | ((long) minY << 32)
                | ((long) (maxY - 1) << 41)
                | ((long) packedFlags << 50);
    }

    public static int state(long interval) {
        return (int) interval;
    }

    public static int minY(long interval) {
        return (int) (interval >>> 32) & 0x1FF;
    }

    public static int maxY(long interval) {
        return ((int) (interval >>> 41) & 0x1FF) + 1;
    }

    public static int flags(long interval) {
        return (int) (interval >>> 50) & FLAG_MASK;
    }

    /** Repräsentierte L0-Fläche dieses Intervalls (1 bis 1024 Blöcke). */
    public static int coverage(long interval) {
        return (((int) (interval >>> 50) >>> COVERAGE_SHIFT) & COVERAGE_MASK) + 1;
    }

    public static boolean landmark(long interval) {
        return (flags(interval) & FLAG_LANDMARK) != 0;
    }

    private LodColumn() {
        this.intervals = new long[0];
    }
}
