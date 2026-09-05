package de.skyengine.shared.world;

/** Immutable, shareable long storage used by packed replicated chunk sections. */
public final class ImmutableLongArray {
    private final long[] values;

    private ImmutableLongArray(long[] values) { this.values = values; }

    public static ImmutableLongArray copyOf(long[] values) {
        if (values == null) throw new NullPointerException("values");
        return new ImmutableLongArray(values.clone());
    }

    /** Takes ownership of an array that the caller promises never to mutate again. */
    public static ImmutableLongArray takeOwnership(long[] values) {
        if (values == null) throw new NullPointerException("values");
        return new ImmutableLongArray(values);
    }

    public int length() { return this.values.length; }
    public long get(int index) { return this.values[index]; }
    public long[] copy() { return this.values.clone(); }
    public boolean sharesStorageWith(ImmutableLongArray other) {
        return other != null && this.values == other.values;
    }
    public long retainedBytes() { return (long) this.values.length * Long.BYTES; }
}
