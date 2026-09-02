package de.skyengine.shared.world;

import java.util.Objects;

/** Immutable network/storage-neutral snapshot of one non-empty 32-cubed L0 section. */
public record ChunkSectionSnapshot(int sectionY, int nonAir, int[] palette, int bitsPerEntry,
                                   long[] packedPaletteIndices, LightPlane skyLight, LightPlane blockLight) {
    public static final int SIZE = 32;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    public ChunkSectionSnapshot {
        if (sectionY < 0 || sectionY >= 16) throw new IllegalArgumentException("Invalid section Y " + sectionY);
        if (nonAir < 1 || nonAir > VOLUME) throw new IllegalArgumentException("Invalid non-air count " + nonAir);
        palette = Objects.requireNonNull(palette).clone();
        if (palette.length < 1 || palette.length > VOLUME) throw new IllegalArgumentException("Invalid palette size");
        if (bitsPerEntry < 0 || bitsPerEntry > 15) throw new IllegalArgumentException("Invalid bits per entry");
        if ((palette.length == 1) != (bitsPerEntry == 0)) {
            throw new IllegalArgumentException("Single-value sections must use zero bits and vice versa");
        }
        packedPaletteIndices = Objects.requireNonNull(packedPaletteIndices).clone();
        int expectedLongs = bitsPerEntry == 0 ? 0 : (int) (((long) VOLUME * bitsPerEntry + 63) / 64);
        if (packedPaletteIndices.length != expectedLongs) {
            throw new IllegalArgumentException("Invalid packed palette length");
        }
        Objects.requireNonNull(skyLight);
        Objects.requireNonNull(blockLight);
    }

    @Override public int[] palette() { return this.palette.clone(); }
    @Override public long[] packedPaletteIndices() { return this.packedPaletteIndices.clone(); }
    public int paletteSize() { return this.palette.length; }
    public int paletteEntry(int index) { return this.palette[index]; }
    public int packedWordCount() { return this.packedPaletteIndices.length; }
    public long packedWord(int index) { return this.packedPaletteIndices[index]; }
}
