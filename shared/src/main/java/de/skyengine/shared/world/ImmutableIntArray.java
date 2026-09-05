package de.skyengine.shared.world;

/** Immutable, shareable int storage used by replicated chunk revisions. */
public final class ImmutableIntArray {
    private final int[] values;

    private ImmutableIntArray(int[] values) { this.values = values; }

    public static ImmutableIntArray copyOf(int[] values) {
        if (values == null) throw new NullPointerException("values");
        return new ImmutableIntArray(values.clone());
    }

    /** Takes ownership of an array that the caller promises never to mutate again. */
    public static ImmutableIntArray takeOwnership(int[] values) {
        if (values == null) throw new NullPointerException("values");
        return new ImmutableIntArray(values);
    }

    public int length() { return this.values.length; }
    public int get(int index) { return this.values[index]; }
    public int[] copy() { return this.values.clone(); }
    public boolean contentEquals(int[] other) { return java.util.Arrays.equals(this.values, other); }
    public boolean sharesStorageWith(ImmutableIntArray other) {
        return other != null && this.values == other.values;
    }
    public long retainedBytes() { return (long) this.values.length * Integer.BYTES; }
}
