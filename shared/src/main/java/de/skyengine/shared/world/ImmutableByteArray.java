package de.skyengine.shared.world;

import java.nio.ByteBuffer;

/** Immutable byte payload with cheap read-only views for codecs and transports. */
public final class ImmutableByteArray {
    private final byte[] values;
    private final int offset;
    private final int length;

    private ImmutableByteArray(byte[] values) { this(values, 0, values.length); }

    private ImmutableByteArray(byte[] values, int offset, int length) {
        this.values = values;
        this.offset = offset;
        this.length = length;
    }

    public static ImmutableByteArray copyOf(byte[] values) {
        if (values == null) throw new NullPointerException("values");
        return new ImmutableByteArray(values.clone());
    }

    /** Takes ownership of an array that the caller promises never to mutate again. */
    public static ImmutableByteArray takeOwnership(byte[] values) {
        if (values == null) throw new NullPointerException("values");
        return new ImmutableByteArray(values);
    }

    public int length() { return this.length; }
    public byte get(int index) {
        if (index < 0 || index >= this.length) throw new IndexOutOfBoundsException(index);
        return this.values[this.offset + index];
    }
    public byte[] copy() {
        return java.util.Arrays.copyOfRange(this.values, this.offset, this.offset + this.length);
    }
    public void copyTo(byte[] target, int targetOffset) {
        System.arraycopy(this.values, this.offset, target, targetOffset, this.length);
    }
    /** Zero-copy immutable sub-view. The backing array remains unreachable and immutable. */
    public ImmutableByteArray slice(int offset, int length) {
        if (offset < 0 || length < 0 || offset > this.length - length) {
            throw new IndexOutOfBoundsException("Invalid immutable byte slice");
        }
        return new ImmutableByteArray(this.values, this.offset + offset, length);
    }
    public ByteBuffer readOnlyView() {
        return ByteBuffer.wrap(this.values, this.offset, this.length).slice().asReadOnlyBuffer();
    }
    public boolean contentEquals(ImmutableByteArray other) {
        if (other == null || this.length != other.length) return false;
        for (int i = 0; i < this.length; i++) {
            if (this.values[this.offset + i] != other.values[other.offset + i]) return false;
        }
        return true;
    }
    public boolean contentEquals(byte[] other) {
        if (other == null || this.length != other.length) return false;
        for (int i = 0; i < this.length; i++) {
            if (this.values[this.offset + i] != other[i]) return false;
        }
        return true;
    }
    public boolean sharesStorageWith(ImmutableByteArray other) {
        return other != null && this.values == other.values
                && this.offset == other.offset && this.length == other.length;
    }
    /** Physical storage retained by this view, intentionally not merely its visible slice. */
    public long retainedBytes() { return this.values.length; }
}
