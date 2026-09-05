package de.skyengine.game.world.chunk.palette;

import de.skyengine.shared.world.ImmutableLongArray;

/**
 * Kompakte Ganzzahl-Indizes fester Bitbreite, gepackt in einem {@code long[]}. Einträge
 * dürfen Long-Grenzen überspannen. Wird von {@link PalettedContainer} als Index-Speicher
 * genutzt (bitsPerEntry = ceil(log2(Paletten-Größe))).
 */
public final class BitStorage {

    private long[] data;
    private ImmutableLongArray frozenData;
    private final int bitsPerEntry;
    private final int size;
    private final long mask;

    public BitStorage(int bitsPerEntry, int size) {
        this.bitsPerEntry = bitsPerEntry;
        this.size = size;
        this.mask = (1L << bitsPerEntry) - 1L;
        this.data = new long[(int) (((long) size * bitsPerEntry + 63) / 64)];
    }

    /** Rebuild aus persistierten Daten (Chunk-Load). Prüft, dass die Array-Länge exakt passt. */
    public BitStorage(int bitsPerEntry, int size, long[] data) {
        int expected = (int) (((long) size * bitsPerEntry + 63) / 64);
        if (data.length != expected) {
            throw new IllegalArgumentException("BitStorage-Länge passt nicht: " + data.length
                    + " Longs statt " + expected + " (bitsPerEntry=" + bitsPerEntry + ", size=" + size + ")");
        }
        this.bitsPerEntry = bitsPerEntry;
        this.size = size;
        this.mask = (1L << bitsPerEntry) - 1L;
        this.data = data;
    }

    private BitStorage(int bitsPerEntry, int size, ImmutableLongArray data) {
        int expected = (int) (((long) size * bitsPerEntry + 63) / 64);
        if (data.length() != expected) {
            throw new IllegalArgumentException("BitStorage-Länge passt nicht: " + data.length()
                    + " Longs statt " + expected + " (bitsPerEntry=" + bitsPerEntry + ", size=" + size + ")");
        }
        this.bitsPerEntry = bitsPerEntry;
        this.size = size;
        this.mask = (1L << bitsPerEntry) - 1L;
        this.frozenData = data;
    }

    /** Uses immutable replicated storage directly and copies only on the first mutation. */
    public static BitStorage adoptImmutable(int bitsPerEntry, int size, ImmutableLongArray data) {
        return new BitStorage(bitsPerEntry, size, data);
    }

    public int get(int index) {
        long bitIndex = (long) index * this.bitsPerEntry;
        int arr = (int) (bitIndex >> 6);
        int off = (int) (bitIndex & 63);
        long value = word(arr) >>> off;
        if (off + this.bitsPerEntry > 64) {
            value |= word(arr + 1) << (64 - off);
        }
        return (int) (value & this.mask);
    }

    public void set(int index, int value) {
        ensureMutable();
        long bitIndex = (long) index * this.bitsPerEntry;
        int arr = (int) (bitIndex >> 6);
        int off = (int) (bitIndex & 63);
        this.data[arr] = (this.data[arr] & ~(this.mask << off)) | (((long) value & this.mask) << off);
        if (off + this.bitsPerEntry > 64) {
            int bitsInFirst = 64 - off;
            this.data[arr + 1] = (this.data[arr + 1] & ~(this.mask >>> bitsInFirst))
                    | (((long) value & this.mask) >>> bitsInFirst);
        }
    }

    public int bitsPerEntry() { return bitsPerEntry; }
    public int size() { return size; }
    public long[] raw() { return this.data != null ? this.data : this.frozenData.copy(); }

    /** Freezes the current packed words for snapshot sharing; later writes are copy-on-write. */
    public ImmutableLongArray freezeData() {
        if (this.frozenData == null) {
            this.frozenData = ImmutableLongArray.takeOwnership(this.data);
            this.data = null;
        }
        return this.frozenData;
    }

    private long word(int index) {
        return this.data != null ? this.data[index] : this.frozenData.get(index);
    }

    private void ensureMutable() {
        if (this.data != null) return;
        this.data = this.frozenData.copy();
        this.frozenData = null;
    }
}
