package de.skyengine.shared.world;

/** Concrete immutable section representation shared by local and remote replication. */
public final class ImmutableChunkSectionData extends ChunkSectionSnapshot {
    public ImmutableChunkSectionData(int sectionY, int nonAir, int[] palette, int bitsPerEntry,
                                     long[] packedPaletteIndices, LightPlane skyLight,
                                     LightPlane blockLight) {
        super(sectionY, nonAir, palette, bitsPerEntry, packedPaletteIndices, skyLight, blockLight);
    }

    private ImmutableChunkSectionData(int sectionY, int nonAir, ImmutableIntArray palette,
                                      int bitsPerEntry, ImmutableLongArray packedPaletteIndices,
                                      LightPlane skyLight, LightPlane blockLight) {
        super(sectionY, nonAir, palette, bitsPerEntry, packedPaletteIndices, skyLight, blockLight);
    }

    /** Adopts arrays freshly produced by a decoder without another copy. */
    public static ImmutableChunkSectionData takeOwnership(
            int sectionY, int nonAir, int[] palette, int bitsPerEntry, long[] packedPaletteIndices,
            LightPlane skyLight, LightPlane blockLight) {
        return new ImmutableChunkSectionData(sectionY, nonAir,
                ImmutableIntArray.takeOwnership(palette), bitsPerEntry,
                ImmutableLongArray.takeOwnership(packedPaletteIndices), skyLight, blockLight);
    }

    public static ImmutableChunkSectionData shared(
            int sectionY, int nonAir, ImmutableIntArray palette, int bitsPerEntry,
            ImmutableLongArray packedPaletteIndices, LightPlane skyLight, LightPlane blockLight) {
        return new ImmutableChunkSectionData(sectionY, nonAir, palette, bitsPerEntry,
                packedPaletteIndices, skyLight, blockLight);
    }
}
