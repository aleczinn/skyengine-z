package de.skyengine.shared.world;

import java.util.Objects;

/**
 * Immutable network/storage-neutral data for one non-empty 32-cubed L0 section.
 * Production snapshots use the {@link ImmutableChunkSectionData} subtype.
 */
public class ChunkSectionSnapshot {
    public static final int SIZE = 32;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    private final int sectionY;
    private final int nonAir;
    private final ImmutableIntArray palette;
    private final int bitsPerEntry;
    private final ImmutableLongArray packedPaletteIndices;
    private final LightPlane skyLight;
    private final LightPlane blockLight;

    public ChunkSectionSnapshot(int sectionY, int nonAir, int[] palette, int bitsPerEntry,
                                long[] packedPaletteIndices, LightPlane skyLight,
                                LightPlane blockLight) {
        this(sectionY, nonAir, ImmutableIntArray.copyOf(palette), bitsPerEntry,
                ImmutableLongArray.copyOf(packedPaletteIndices), skyLight, blockLight);
    }

    protected ChunkSectionSnapshot(int sectionY, int nonAir, ImmutableIntArray palette,
                                   int bitsPerEntry, ImmutableLongArray packedPaletteIndices,
                                   LightPlane skyLight, LightPlane blockLight) {
        if (sectionY < 0 || sectionY >= 16) throw new IllegalArgumentException("Invalid section Y " + sectionY);
        if (nonAir < 1 || nonAir > VOLUME) throw new IllegalArgumentException("Invalid non-air count " + nonAir);
        this.palette = Objects.requireNonNull(palette);
        if (palette.length() < 1 || palette.length() > VOLUME) throw new IllegalArgumentException("Invalid palette size");
        if (bitsPerEntry < 0 || bitsPerEntry > 15) throw new IllegalArgumentException("Invalid bits per entry");
        if ((palette.length() == 1) != (bitsPerEntry == 0)) {
            throw new IllegalArgumentException("Single-value sections must use zero bits and vice versa");
        }
        this.packedPaletteIndices = Objects.requireNonNull(packedPaletteIndices);
        int expectedLongs = bitsPerEntry == 0 ? 0 : (int) (((long) VOLUME * bitsPerEntry + 63) / 64);
        if (packedPaletteIndices.length() != expectedLongs) {
            throw new IllegalArgumentException("Invalid packed palette length");
        }
        this.sectionY = sectionY;
        this.nonAir = nonAir;
        this.bitsPerEntry = bitsPerEntry;
        this.skyLight = Objects.requireNonNull(skyLight);
        this.blockLight = Objects.requireNonNull(blockLight);
    }

    public int sectionY() { return this.sectionY; }
    public int nonAir() { return this.nonAir; }
    public int bitsPerEntry() { return this.bitsPerEntry; }
    public LightPlane skyLight() { return this.skyLight; }
    public LightPlane blockLight() { return this.blockLight; }
    public int[] palette() { return this.palette.copy(); }
    public long[] packedPaletteIndices() { return this.packedPaletteIndices.copy(); }
    public ImmutableIntArray paletteData() { return this.palette; }
    public ImmutableLongArray packedPaletteData() { return this.packedPaletteIndices; }
    public int paletteSize() { return this.palette.length(); }
    public int paletteEntry(int index) { return this.palette.get(index); }
    public int packedWordCount() { return this.packedPaletteIndices.length(); }
    public long packedWord(int index) { return this.packedPaletteIndices.get(index); }
    public long retainedBytes() {
        return this.palette.retainedBytes() + this.packedPaletteIndices.retainedBytes()
                + this.skyLight.retainedBytes() + this.blockLight.retainedBytes();
    }

    /** Physical payload first introduced by this revision relative to an older section. */
    public long newlyAllocatedBytesComparedTo(ChunkSectionSnapshot previous) {
        if (previous == this) return 0;
        long bytes = 0;
        if (previous == null || !this.palette.sharesStorageWith(previous.palette)) {
            bytes += this.palette.retainedBytes();
        }
        if (previous == null || !this.packedPaletteIndices.sharesStorageWith(
                previous.packedPaletteIndices)) {
            bytes += this.packedPaletteIndices.retainedBytes();
        }
        if (previous == null || this.skyLight != previous.skyLight) bytes += this.skyLight.retainedBytes();
        if (previous == null || this.blockLight != previous.blockLight) bytes += this.blockLight.retainedBytes();
        return bytes;
    }
}
