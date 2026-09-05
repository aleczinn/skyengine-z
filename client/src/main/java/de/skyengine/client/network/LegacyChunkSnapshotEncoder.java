package de.skyengine.client.network;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.ImmutableChunkColumnData;
import de.skyengine.shared.world.ImmutableChunkSectionData;
import de.skyengine.shared.world.BlockChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import de.skyengine.shared.world.BlockEntitySnapshot;

/**
 * Migration adapter from the existing L0 chunk representation to the transport-neutral snapshot.
 * The copied snapshot is safe to compress on a worker after the chunk read lock has been released.
 */
public final class LegacyChunkSnapshotEncoder {
    private static final int DEFAULT_TINT = 0xFFFFFF;

    private LegacyChunkSnapshotEncoder() {
    }

    public static ChunkColumnSnapshot encode(String dimension, WorldGenerator generator, Chunk chunk) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(chunk, "chunk");

        int[] biomeIds = sampleBiomes(generator, chunk.chunkX, chunk.chunkZ);
        int[][] generatedTints = null;
        if (chunk.grassTintCorners == null || chunk.foliageTintCorners == null) {
            generatedTints = generateTintFallback(generator, chunk.chunkX, chunk.chunkZ);
        }

        Lock write = chunk.writeLock();
        write.lock();
        try {
            List<ChunkSectionSnapshot> sections = new ArrayList<>(Chunk.SECTIONS);
            for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                ChunkSection section = chunk.getSection(sectionY);
                if (section == null || section.isEmpty()) continue;
                sections.add(copySection(chunk, section, sectionY));
            }
            int[] grass = chunk.grassTintCorners == null
                    ? generatedTints[0] : chunk.grassTintCorners.clone();
            int[] foliage = chunk.foliageTintCorners == null
                    ? generatedTints[1] : chunk.foliageTintCorners.clone();
            int[] heightmap = chunk.heightmap == null ? deriveHeightmap(chunk) : chunk.heightmap.clone();
            return ImmutableChunkColumnData.takeOwnership(dimension, chunk.chunkX, chunk.chunkZ,
                    Math.max(0, chunk.modificationEpoch()), sections, biomeIds, grass, foliage, heightmap,
                    List.of());
        } finally {
            write.unlock();
        }
    }

    /**
     * Advances only the immutable confirmed basis. The mutable presentation chunk is
     * deliberately not an input because it may contain unconfirmed prediction overlays.
     */
    public static ChunkColumnSnapshot applyConfirmedBlockChanges(ChunkColumnSnapshot previous,
                                                                 long revision,
                                                                 List<BlockChange> changes) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(changes, "changes");
        java.util.Map<Integer, List<BlockChange>> bySection = new java.util.HashMap<>();
        for (BlockChange change : changes) {
            bySection.computeIfAbsent(change.y() >> ChunkSection.SHIFT,
                    ignored -> new ArrayList<>()).add(change);
        }

        List<ChunkSectionSnapshot> sections = new ArrayList<>(
                previous.sections().size() + bySection.size());
        for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
            ChunkSectionSnapshot old = section(previous, sectionY);
            List<BlockChange> sectionChanges = bySection.get(sectionY);
            if (sectionChanges == null) {
                if (old != null) sections.add(old);
                continue;
            }
            PalettedContainer blocks = old == null
                    ? new PalettedContainer(ChunkSection.VOLUME, 0)
                    : PalettedContainer.adoptImmutable(ChunkSection.VOLUME, old.paletteData(),
                    old.bitsPerEntry(), old.packedPaletteData(), old.nonAir());
            for (BlockChange change : sectionChanges) {
                int localY = change.y() & ChunkSection.MASK;
                int index = (localY << (ChunkSection.SHIFT * 2))
                        | (change.localZ() << ChunkSection.SHIFT) | change.localX();
                blocks.set(index, change.stateId());
            }
            if (blocks.isEmpty()) continue;
            PalettedContainer.FrozenData frozen = blocks.freezeData();
            sections.add(ImmutableChunkSectionData.shared(sectionY, frozen.nonAir(), frozen.palette(),
                    frozen.bitsPerEntry(), frozen.packedIndices(),
                    old == null ? ZERO_LIGHT : old.skyLight(),
                    old == null ? ZERO_LIGHT : old.blockLight()));
        }

        var heightmap = updateHeightmap(previous, sections, changes);
        List<BlockEntitySnapshot> blockEntities = new ArrayList<>(previous.blockEntities());
        blockEntities.removeIf(entity -> changes.stream().anyMatch(change ->
                entity.localX() == change.localX() && entity.y() == change.y()
                        && entity.localZ() == change.localZ()));
        return ImmutableChunkColumnData.shared(previous.dimension(), previous.chunkX(), previous.chunkZ(),
                revision, sections, previous.biomeData(), previous.grassTintData(),
                previous.foliageTintData(), heightmap, blockEntities);
    }

    private static final de.skyengine.shared.world.LightPlane ZERO_LIGHT =
            new de.skyengine.shared.world.LightPlane(
                    de.skyengine.shared.world.LightPlane.Mode.UNIFORM_ZERO, null);

    private static de.skyengine.shared.world.ImmutableIntArray updateHeightmap(
            ChunkColumnSnapshot previous, List<ChunkSectionSnapshot> sections,
            List<BlockChange> changes) {
        java.util.HashSet<Integer> affected = new java.util.HashSet<>();
        for (BlockChange change : changes) {
            int column = (change.localZ() << ChunkSection.SHIFT) | change.localX();
            if (change.y() + 1 >= previous.height(column)) affected.add(column);
        }
        if (affected.isEmpty()) return previous.heightmapData();
        int[] heights = previous.heightmap();
        for (int column : affected) {
            int x = column & ChunkSection.MASK;
            int z = column >>> ChunkSection.SHIFT;
            int height = 0;
            for (int y = Chunk.HEIGHT - 1; y >= 0; y--) {
                if (stateAt(sections, x, y, z) != 0) {
                    height = y + 1;
                    break;
                }
            }
            heights[column] = height;
        }
        return de.skyengine.shared.world.ImmutableIntArray.takeOwnership(heights);
    }

    private static int stateAt(List<ChunkSectionSnapshot> sections, int x, int y, int z) {
        ChunkSectionSnapshot section = section(sections, y >> ChunkSection.SHIFT);
        if (section == null) return 0;
        int index = ((y & ChunkSection.MASK) << (ChunkSection.SHIFT * 2))
                | (z << ChunkSection.SHIFT) | x;
        if (section.bitsPerEntry() == 0) return section.paletteEntry(0);
        long bitIndex = (long) index * section.bitsPerEntry();
        int word = (int) (bitIndex >>> 6);
        int offset = (int) (bitIndex & 63);
        long value = section.packedWord(word) >>> offset;
        if (offset + section.bitsPerEntry() > 64) {
            value |= section.packedWord(word + 1) << (64 - offset);
        }
        int paletteIndex = (int) (value & ((1L << section.bitsPerEntry()) - 1));
        return section.paletteEntry(paletteIndex);
    }

    private static ChunkSectionSnapshot section(ChunkColumnSnapshot snapshot, int sectionY) {
        return section(snapshot.sections(), sectionY);
    }

    private static ChunkSectionSnapshot section(List<ChunkSectionSnapshot> sections, int sectionY) {
        for (ChunkSectionSnapshot section : sections) {
            if (section.sectionY() == sectionY) return section;
        }
        return null;
    }

    private static ChunkSectionSnapshot copySection(Chunk chunk, ChunkSection section, int sectionY) {
        PalettedContainer container = section.container();
        PalettedContainer.FrozenData frozen = container.freezeData();
        return ImmutableChunkSectionData.shared(sectionY, frozen.nonAir(), frozen.palette(),
                frozen.bitsPerEntry(), frozen.packedIndices(),
                chunk.light.snapshotSection(sectionY), chunk.blockLight.snapshotSection(sectionY));
    }

    private static int[] deriveHeightmap(Chunk chunk) {
        int[] heightmap = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                int height = 0;
                for (int y = Chunk.HEIGHT - 1; y >= 0; y--) {
                    if (chunk.getBlock(x, y, z) != 0) {
                        height = y + 1;
                        break;
                    }
                }
                heightmap[(z << ChunkSection.SHIFT) | x] = height;
            }
        }
        return heightmap;
    }

    private static int[] sampleBiomes(WorldGenerator generator, int chunkX, int chunkZ) {
        int[] biomes = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        int baseX = chunkX << ChunkSection.SHIFT;
        int baseZ = chunkZ << ChunkSection.SHIFT;
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                Biome biome = generator.biomeAt(baseX + x, baseZ + z);
                biomes[(z << ChunkSection.SHIFT) | x] = biome == null ? 0 : biome.id;
            }
        }
        return biomes;
    }

    private static int[][] generateTintFallback(WorldGenerator generator, int chunkX, int chunkZ) {
        Chunk tintChunk = new Chunk(chunkX, chunkZ);
        generator.fillTintCorners(tintChunk);
        if (tintChunk.grassTintCorners != null && tintChunk.foliageTintCorners != null) {
            return new int[][]{tintChunk.grassTintCorners.clone(), tintChunk.foliageTintCorners.clone()};
        }
        int[] grass = new int[ChunkColumnSnapshot.TINT_CORNERS];
        int[] foliage = new int[ChunkColumnSnapshot.TINT_CORNERS];
        int baseX = chunkX << ChunkSection.SHIFT;
        int baseZ = chunkZ << ChunkSection.SHIFT;
        for (int x = 0; x <= ChunkSection.SIZE; x++) {
            for (int z = 0; z <= ChunkSection.SIZE; z++) {
                Biome biome = generator.biomeAt(baseX + x, baseZ + z);
                int index = x * (ChunkSection.SIZE + 1) + z;
                grass[index] = biome == null ? DEFAULT_TINT : biome.grassTint;
                foliage[index] = biome == null ? DEFAULT_TINT : biome.foliageTint;
            }
        }
        return new int[][]{grass, foliage};
    }
}
