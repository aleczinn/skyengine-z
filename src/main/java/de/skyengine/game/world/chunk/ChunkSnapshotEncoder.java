package de.skyengine.game.world.chunk;

import de.skyengine.game.world.chunk.palette.BitStorage;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.save.DataTagIO;
import de.skyengine.shared.world.BlockEntitySnapshot;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts the existing authoritative L0 representation into an immutable network snapshot. */
public final class ChunkSnapshotEncoder {
    private static final int COLUMN_SIZE = ChunkSection.SIZE * ChunkSection.SIZE;
    private static final int TINT_SIZE = (ChunkSection.SIZE + 1) * (ChunkSection.SIZE + 1);

    private ChunkSnapshotEncoder() {
    }

    /**
     * Takes a consistent snapshot while mesh/save workers may read the same chunk. Expensive
     * compression remains outside the lock and is handled by the replication worker.
     */
    public static ChunkColumnSnapshot encode(String dimension, Chunk chunk, WorldGenerator generator) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(generator, "generator");

        return capture(dimension, chunk, generator);
    }

    /**
     * Copies mutable chunk-owned data exactly once while holding the read lock. The immutable
     * snapshot constructors perform that ownership copy; pre-cloning the four column arrays
     * used to copy another ~17 KiB per streamed column for no consistency benefit.
     */
    private static ChunkColumnSnapshot capture(String dimension, Chunk chunk,
                                               WorldGenerator generator) {
        chunk.readLock().lock();
        try {
            int baseX = chunk.chunkX << ChunkSection.SHIFT;
            int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
            List<ChunkSectionSnapshot> sections = new ArrayList<>();
            for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                ChunkSection section = chunk.getSection(sectionY);
                if (section == null || section.isEmpty()) continue;
                PalettedContainer container = section.container();
                BitStorage storage = container.storage();
                sections.add(new ChunkSectionSnapshot(sectionY, container.nonAir(),
                        container.paletteEntries(), storage == null ? 0 : storage.bitsPerEntry(),
                        storage == null ? new long[0] : storage.raw(),
                        chunk.light.snapshotSection(sectionY),
                        chunk.blockLight.snapshotSection(sectionY)));
            }

            int[] biomeIds = chunk.biomeIds;
            if (biomeIds == null) {
                biomeIds = new int[COLUMN_SIZE];
                for (int z = 0; z < ChunkSection.SIZE; z++) {
                    for (int x = 0; x < ChunkSection.SIZE; x++) {
                        biomeIds[(z << ChunkSection.SHIFT) | x] =
                                generator.biomeAt(baseX + x, baseZ + z).id;
                    }
                }
            }
            int[] grass = chunk.grassTintCorners == null
                    ? tintCorners(generator, baseX, baseZ, true) : chunk.grassTintCorners;
            int[] foliage = chunk.foliageTintCorners == null
                    ? tintCorners(generator, baseX, baseZ, false) : chunk.foliageTintCorners;
            int[] heightmap = chunk.heightmap == null ? rebuildHeightmap(chunk) : chunk.heightmap;
            List<BlockEntitySnapshot> blockEntities = snapshotBlockEntities(chunk);
            return new ChunkColumnSnapshot(dimension, chunk.chunkX, chunk.chunkZ,
                    chunk.modificationEpoch(), sections, biomeIds, grass, foliage,
                    heightmap, blockEntities);
        } finally {
            chunk.readLock().unlock();
        }
    }

    private static List<BlockEntitySnapshot> snapshotBlockEntities(Chunk chunk) {
        if (chunk.blockEntities().isEmpty()) return List.of();
        List<BlockEntitySnapshot> result = new ArrayList<>(chunk.blockEntities().size());
        for (BlockEntity entity : chunk.blockEntities()) {
            BlockEntitySnapshot snapshot = encodeBlockEntity(entity);
            if (snapshot != null) result.add(snapshot);
        }
        return List.copyOf(result);
    }

    /** Serializes one dirty block entity without rebuilding its complete chunk snapshot. */
    public static BlockEntitySnapshot encodeBlockEntity(BlockEntity entity) {
        Objects.requireNonNull(entity, "entity");
        var typeId = Registries.BLOCK_ENTITY.idOf(entity.getType());
        if (typeId == null) return null;
        DataTag tag = new DataTag();
        entity.saveNetwork(tag);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            DataTagIO.write(tag, new DataOutputStream(bytes));
            var pos = entity.getPos();
            return new BlockEntitySnapshot(pos.x() & ChunkSection.MASK, pos.y(),
                    pos.z() & ChunkSection.MASK, typeId.toString(), bytes.toByteArray());
        } catch (IOException failure) {
            throw new UncheckedIOException("BlockEntity-Netzwerksnapshot fehlgeschlagen", failure);
        }
    }

    private static int[] tintCorners(WorldGenerator generator, int baseX, int baseZ, boolean grass) {
        int[] result = new int[TINT_SIZE];
        int stride = ChunkSection.SIZE + 1;
        for (int z = 0; z <= ChunkSection.SIZE; z++) {
            for (int x = 0; x <= ChunkSection.SIZE; x++) {
                Biome biome = generator.biomeAt(baseX + x, baseZ + z);
                result[x * stride + z] = grass ? biome.grassTint : biome.foliageTint;
            }
        }
        return result;
    }

    private static int[] rebuildHeightmap(Chunk chunk) {
        int[] result = new int[COLUMN_SIZE];
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                int y = Chunk.HEIGHT - 1;
                while (y >= 0 && chunk.getBlock(x, y, z) == 0) y--;
                result[(z << ChunkSection.SHIFT) | x] = y + 1;
            }
        }
        return result;
    }
}
