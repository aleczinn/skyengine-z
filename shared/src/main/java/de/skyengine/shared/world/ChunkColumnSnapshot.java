package de.skyengine.shared.world;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable complete client-visible state for one L0 chunk column. Empty sections are omitted.
 * This compatibility type is the common semantic message above Local/TCP transports.
 */
public class ChunkColumnSnapshot {
    public static final int COLUMN_CELLS = 32 * 32;
    public static final int TINT_CORNERS = 33 * 33;

    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final long revision;
    private final List<ChunkSectionSnapshot> sections;
    private final ImmutableIntArray biomeIds;
    private final ImmutableIntArray grassTintCorners;
    private final ImmutableIntArray foliageTintCorners;
    private final ImmutableIntArray heightmap;
    private final List<BlockEntitySnapshot> blockEntities;

    public ChunkColumnSnapshot(String dimension, int chunkX, int chunkZ, long revision,
                               List<ChunkSectionSnapshot> sections, int[] biomeIds,
                               int[] grassTintCorners, int[] foliageTintCorners, int[] heightmap,
                               List<BlockEntitySnapshot> blockEntities) {
        this(dimension, chunkX, chunkZ, revision, sections,
                checkedCopy(biomeIds, COLUMN_CELLS, "biome IDs"),
                checkedCopy(grassTintCorners, TINT_CORNERS, "grass tint grid"),
                checkedCopy(foliageTintCorners, TINT_CORNERS, "foliage tint grid"),
                checkedCopy(heightmap, COLUMN_CELLS, "heightmap"), blockEntities);
    }

    protected ChunkColumnSnapshot(String dimension, int chunkX, int chunkZ, long revision,
                                  List<ChunkSectionSnapshot> sections, ImmutableIntArray biomeIds,
                                  ImmutableIntArray grassTintCorners,
                                  ImmutableIntArray foliageTintCorners,
                                  ImmutableIntArray heightmap,
                                  List<BlockEntitySnapshot> blockEntities) {
        this.dimension = Objects.requireNonNull(dimension);
        if (revision < 0) throw new IllegalArgumentException("Negative chunk revision");
        this.revision = revision;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sections = List.copyOf(sections);
        Set<Integer> sectionYs = new HashSet<>();
        for (ChunkSectionSnapshot section : this.sections) {
            if (!sectionYs.add(section.sectionY())) throw new IllegalArgumentException("Duplicate section Y");
        }
        this.biomeIds = checked(biomeIds, COLUMN_CELLS, "biome IDs");
        this.grassTintCorners = checked(grassTintCorners, TINT_CORNERS, "grass tint grid");
        this.foliageTintCorners = checked(foliageTintCorners, TINT_CORNERS, "foliage tint grid");
        this.heightmap = checked(heightmap, COLUMN_CELLS, "heightmap");
        this.blockEntities = List.copyOf(blockEntities);
    }

    /** Source compatibility for snapshots without block-entity replication. */
    public ChunkColumnSnapshot(String dimension, int chunkX, int chunkZ, long revision,
                               List<ChunkSectionSnapshot> sections, int[] biomeIds,
                               int[] grassTintCorners, int[] foliageTintCorners, int[] heightmap) {
        this(dimension, chunkX, chunkZ, revision, sections, biomeIds, grassTintCorners,
                foliageTintCorners, heightmap, List.of());
    }

    public String dimension() { return this.dimension; }
    public int chunkX() { return this.chunkX; }
    public int chunkZ() { return this.chunkZ; }
    public long revision() { return this.revision; }
    public List<ChunkSectionSnapshot> sections() { return this.sections; }
    public List<BlockEntitySnapshot> blockEntities() { return this.blockEntities; }
    public int[] biomeIds() { return this.biomeIds.copy(); }
    public int[] grassTintCorners() { return this.grassTintCorners.copy(); }
    public int[] foliageTintCorners() { return this.foliageTintCorners.copy(); }
    public int[] heightmap() { return this.heightmap.copy(); }
    public ImmutableIntArray biomeData() { return this.biomeIds; }
    public ImmutableIntArray grassTintData() { return this.grassTintCorners; }
    public ImmutableIntArray foliageTintData() { return this.foliageTintCorners; }
    public ImmutableIntArray heightmapData() { return this.heightmap; }
    public int biomeId(int index) { return this.biomeIds.get(index); }
    public int grassTintCorner(int index) { return this.grassTintCorners.get(index); }
    public int foliageTintCorner(int index) { return this.foliageTintCorners.get(index); }
    public int height(int index) { return this.heightmap.get(index); }
    public long retainedBytes() {
        long bytes = this.biomeIds.retainedBytes() + this.grassTintCorners.retainedBytes()
                + this.foliageTintCorners.retainedBytes() + this.heightmap.retainedBytes();
        for (ChunkSectionSnapshot section : this.sections) bytes += section.retainedBytes();
        for (BlockEntitySnapshot blockEntity : this.blockEntities) {
            bytes += blockEntity.dataPayload().retainedBytes();
        }
        return bytes;
    }

    /** Counts newly allocated immutable payload, excluding storage shared with the previous revision. */
    public long newlyAllocatedBytesComparedTo(ChunkColumnSnapshot previous) {
        long bytes = 0;
        if (previous == null || !this.biomeIds.sharesStorageWith(previous.biomeIds)) {
            bytes += this.biomeIds.retainedBytes();
        }
        if (previous == null || !this.grassTintCorners.sharesStorageWith(previous.grassTintCorners)) {
            bytes += this.grassTintCorners.retainedBytes();
        }
        if (previous == null || !this.foliageTintCorners.sharesStorageWith(previous.foliageTintCorners)) {
            bytes += this.foliageTintCorners.retainedBytes();
        }
        if (previous == null || !this.heightmap.sharesStorageWith(previous.heightmap)) {
            bytes += this.heightmap.retainedBytes();
        }
        for (ChunkSectionSnapshot section : this.sections) {
            ChunkSectionSnapshot old = previous == null ? null : previous.section(section.sectionY());
            bytes += section.newlyAllocatedBytesComparedTo(old);
        }
        for (BlockEntitySnapshot blockEntity : this.blockEntities) {
            BlockEntitySnapshot old = previous == null ? null : previous.blockEntity(
                    blockEntity.localX(), blockEntity.y(), blockEntity.localZ());
            if (old == null || !blockEntity.dataPayload().sharesStorageWith(old.dataPayload())) {
                bytes += blockEntity.dataPayload().retainedBytes();
            }
        }
        return bytes;
    }

    private ChunkSectionSnapshot section(int sectionY) {
        for (ChunkSectionSnapshot section : this.sections) {
            if (section.sectionY() == sectionY) return section;
        }
        return null;
    }

    private BlockEntitySnapshot blockEntity(int localX, int y, int localZ) {
        for (BlockEntitySnapshot blockEntity : this.blockEntities) {
            if (blockEntity.localX() == localX && blockEntity.y() == y
                    && blockEntity.localZ() == localZ) return blockEntity;
        }
        return null;
    }

    private static ImmutableIntArray checkedCopy(int[] values, int expected, String name) {
        Objects.requireNonNull(values);
        if (values.length != expected) throw new IllegalArgumentException("Invalid " + name + " length");
        return ImmutableIntArray.copyOf(values);
    }

    private static ImmutableIntArray checked(ImmutableIntArray values, int expected, String name) {
        Objects.requireNonNull(values);
        if (values.length() != expected) throw new IllegalArgumentException("Invalid " + name + " length");
        return values;
    }
}
