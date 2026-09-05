package de.skyengine.shared.world;

import java.util.List;

/** Concrete common immutable chunk revision used on both sides of every transport. */
public final class ImmutableChunkColumnData extends ChunkColumnSnapshot {
    public ImmutableChunkColumnData(String dimension, int chunkX, int chunkZ, long revision,
                                    List<ChunkSectionSnapshot> sections, int[] biomeIds,
                                    int[] grassTintCorners, int[] foliageTintCorners, int[] heightmap,
                                    List<BlockEntitySnapshot> blockEntities) {
        super(dimension, chunkX, chunkZ, revision, sections, biomeIds, grassTintCorners,
                foliageTintCorners, heightmap, blockEntities);
    }

    private ImmutableChunkColumnData(String dimension, int chunkX, int chunkZ, long revision,
                                     List<ChunkSectionSnapshot> sections, ImmutableIntArray biomeIds,
                                     ImmutableIntArray grassTintCorners,
                                     ImmutableIntArray foliageTintCorners,
                                     ImmutableIntArray heightmap,
                                     List<BlockEntitySnapshot> blockEntities) {
        super(dimension, chunkX, chunkZ, revision, sections, biomeIds, grassTintCorners,
                foliageTintCorners, heightmap, blockEntities);
    }

    /** Adopts arrays freshly produced by a decoder without cloning them again. */
    public static ImmutableChunkColumnData takeOwnership(
            String dimension, int chunkX, int chunkZ, long revision,
            List<ChunkSectionSnapshot> sections, int[] biomeIds, int[] grassTintCorners,
            int[] foliageTintCorners, int[] heightmap, List<BlockEntitySnapshot> blockEntities) {
        return new ImmutableChunkColumnData(dimension, chunkX, chunkZ, revision, sections,
                ImmutableIntArray.takeOwnership(biomeIds), ImmutableIntArray.takeOwnership(grassTintCorners),
                ImmutableIntArray.takeOwnership(foliageTintCorners), ImmutableIntArray.takeOwnership(heightmap),
                blockEntities);
    }

    /** Creates a new revision while sharing all unchanged immutable column data. */
    public static ImmutableChunkColumnData shared(
            String dimension, int chunkX, int chunkZ, long revision,
            List<ChunkSectionSnapshot> sections, ImmutableIntArray biomeIds,
            ImmutableIntArray grassTintCorners, ImmutableIntArray foliageTintCorners,
            ImmutableIntArray heightmap, List<BlockEntitySnapshot> blockEntities) {
        return new ImmutableChunkColumnData(dimension, chunkX, chunkZ, revision, sections,
                biomeIds, grassTintCorners, foliageTintCorners, heightmap, blockEntities);
    }
}
