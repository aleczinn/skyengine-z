package de.skyengine.shared.world;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete client-visible state for a single L0 chunk column. Empty sections are omitted. */
public record ChunkColumnSnapshot(String dimension, int chunkX, int chunkZ, long revision,
                                  List<ChunkSectionSnapshot> sections, int[] biomeIds,
                                  int[] grassTintCorners, int[] foliageTintCorners, int[] heightmap,
                                  List<BlockEntitySnapshot> blockEntities) {
    public static final int COLUMN_CELLS = 32 * 32;
    public static final int TINT_CORNERS = 33 * 33;

    public ChunkColumnSnapshot {
        Objects.requireNonNull(dimension);
        if (revision < 0) throw new IllegalArgumentException("Negative chunk revision");
        sections = List.copyOf(sections);
        Set<Integer> sectionYs = new HashSet<>();
        for (ChunkSectionSnapshot section : sections) {
            if (!sectionYs.add(section.sectionY())) throw new IllegalArgumentException("Duplicate section Y");
        }
        biomeIds = checkedCopy(biomeIds, COLUMN_CELLS, "biome IDs");
        grassTintCorners = checkedCopy(grassTintCorners, TINT_CORNERS, "grass tint grid");
        foliageTintCorners = checkedCopy(foliageTintCorners, TINT_CORNERS, "foliage tint grid");
        heightmap = checkedCopy(heightmap, COLUMN_CELLS, "heightmap");
        blockEntities = List.copyOf(blockEntities);
    }

    /** Source compatibility for snapshots without block-entity replication. */
    public ChunkColumnSnapshot(String dimension, int chunkX, int chunkZ, long revision,
                               List<ChunkSectionSnapshot> sections, int[] biomeIds,
                               int[] grassTintCorners, int[] foliageTintCorners, int[] heightmap) {
        this(dimension, chunkX, chunkZ, revision, sections, biomeIds, grassTintCorners,
                foliageTintCorners, heightmap, List.of());
    }

    @Override public int[] biomeIds() { return this.biomeIds.clone(); }
    @Override public int[] grassTintCorners() { return this.grassTintCorners.clone(); }
    @Override public int[] foliageTintCorners() { return this.foliageTintCorners.clone(); }
    @Override public int[] heightmap() { return this.heightmap.clone(); }
    public int biomeId(int index) { return this.biomeIds[index]; }
    public int grassTintCorner(int index) { return this.grassTintCorners[index]; }
    public int foliageTintCorner(int index) { return this.foliageTintCorners[index]; }
    public int height(int index) { return this.heightmap[index]; }

    private static int[] checkedCopy(int[] values, int expected, String name) {
        Objects.requireNonNull(values);
        if (values.length != expected) throw new IllegalArgumentException("Invalid " + name + " length");
        return values.clone();
    }
}
