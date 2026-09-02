package de.skyengine.client.network;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.BitStorage;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.light.LightStorage;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import de.skyengine.game.world.chunk.ChunkSnapshotEncoder;
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

        Lock read = chunk.readLock();
        read.lock();
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
            return new ChunkColumnSnapshot(dimension, chunk.chunkX, chunk.chunkZ,
                    Math.max(0, chunk.modificationEpoch()), sections, biomeIds, grass, foliage, heightmap);
        } finally {
            read.unlock();
        }
    }

    /** Rebuilds a cached network snapshot from the already updated replicated chunk. */
    public static ChunkColumnSnapshot encodeReplicated(ChunkColumnSnapshot previous, Chunk chunk,
                                                       long revision) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(chunk, "chunk");
        Lock read = chunk.readLock();
        read.lock();
        try {
            List<ChunkSectionSnapshot> sections = new ArrayList<>(Chunk.SECTIONS);
            for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                ChunkSection section = chunk.getSection(sectionY);
                if (section != null && !section.isEmpty()) sections.add(copySection(chunk, section, sectionY));
            }
            List<BlockEntitySnapshot> blockEntities = new ArrayList<>();
            for (var entity : chunk.blockEntities()) {
                BlockEntitySnapshot snapshot = ChunkSnapshotEncoder.encodeBlockEntity(entity);
                if (snapshot != null) blockEntities.add(snapshot);
            }
            return new ChunkColumnSnapshot(previous.dimension(), chunk.chunkX, chunk.chunkZ, revision,
                    sections, previous.biomeIds(), previous.grassTintCorners(),
                    previous.foliageTintCorners(), chunk.heightmap == null
                            ? previous.heightmap() : chunk.heightmap.clone(), blockEntities);
        } finally {
            read.unlock();
        }
    }

    private static ChunkSectionSnapshot copySection(Chunk chunk, ChunkSection section, int sectionY) {
        PalettedContainer container = section.container();
        int[] palette = container.paletteEntries();
        BitStorage storage = container.storage();
        int bits = storage == null ? 0 : storage.bitsPerEntry();
        long[] words = storage == null ? new long[0] : storage.raw().clone();
        return new ChunkSectionSnapshot(sectionY, container.nonAir(), palette, bits, words,
                copyLight(chunk.light, sectionY), copyLight(chunk.blockLight, sectionY));
    }

    private static LightPlane copyLight(LightStorage storage, int sectionY) {
        int uniform = storage.uniformValue(sectionY);
        if (uniform == 0) return new LightPlane(LightPlane.Mode.UNIFORM_ZERO, null);
        if (uniform == 15) return new LightPlane(LightPlane.Mode.UNIFORM_FULL, null);

        byte[] packed = new byte[LightPlane.PACKED_BYTES];
        if (uniform >= 0) {
            Arrays.fill(packed, (byte) (uniform | uniform << 4));
        } else {
            int baseY = sectionY << ChunkSection.SHIFT;
            int cell = 0;
            for (int y = 0; y < ChunkSection.SIZE; y++) {
                for (int z = 0; z < ChunkSection.SIZE; z++) {
                    for (int x = 0; x < ChunkSection.SIZE; x += 2) {
                        int low = storage.get(x, baseY + y, z);
                        int high = storage.get(x + 1, baseY + y, z);
                        packed[cell++] = (byte) (low | high << 4);
                    }
                }
            }
        }
        return new LightPlane(LightPlane.Mode.PACKED_NIBBLES, packed);
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
