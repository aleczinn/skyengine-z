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

    private record Capture(int chunkX, int chunkZ, long revision,
                           List<ChunkSectionSnapshot> sections, int[] grass, int[] foliage,
                           int[] heightmap, List<BlockEntitySnapshot> blockEntities) { }

    /**
     * Takes a consistent snapshot while mesh/save workers may read the same chunk. Expensive
     * compression remains outside the lock and is handled by the replication worker.
     */
    public static ChunkColumnSnapshot encode(String dimension, Chunk chunk, WorldGenerator generator) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(generator, "generator");

        Capture capture = capture(chunk);
        int baseX = capture.chunkX() << ChunkSection.SHIFT;
        int baseZ = capture.chunkZ() << ChunkSection.SHIFT;
        int[] biomeIds = new int[COLUMN_SIZE];
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                biomeIds[(z << ChunkSection.SHIFT) | x] = generator.biomeAt(baseX + x, baseZ + z).id;
            }
        }
        int[] grass = capture.grass() == null
                ? tintCorners(generator, baseX, baseZ, true) : capture.grass();
        int[] foliage = capture.foliage() == null
                ? tintCorners(generator, baseX, baseZ, false) : capture.foliage();
        return new ChunkColumnSnapshot(dimension, capture.chunkX(), capture.chunkZ(), capture.revision(),
                capture.sections(), biomeIds, grass, foliage, capture.heightmap(), capture.blockEntities());
    }

    /** Copies only mutable chunk-owned data while holding the read lock. */
    private static Capture capture(Chunk chunk) {
        chunk.readLock().lock();
        try {
            List<ChunkSectionSnapshot> sections = new ArrayList<>();
            for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                ChunkSection section = chunk.getSection(sectionY);
                if (section == null || section.isEmpty()) continue;
                PalettedContainer container = section.container();
                BitStorage storage = container.storage();
                sections.add(new ChunkSectionSnapshot(sectionY, container.nonAir(),
                        container.paletteEntries(), storage == null ? 0 : storage.bitsPerEntry(),
                        storage == null ? new long[0] : storage.raw().clone(),
                        chunk.light.snapshotSection(sectionY),
                        chunk.blockLight.snapshotSection(sectionY)));
            }

            int[] grass = chunk.grassTintCorners == null
                    ? null : chunk.grassTintCorners.clone();
            int[] foliage = chunk.foliageTintCorners == null
                    ? null : chunk.foliageTintCorners.clone();
            int[] heightmap = chunk.heightmap == null ? rebuildHeightmap(chunk) : chunk.heightmap.clone();
            List<BlockEntitySnapshot> blockEntities = snapshotBlockEntities(chunk);
            return new Capture(chunk.chunkX, chunk.chunkZ, chunk.modificationEpoch(), List.copyOf(sections),
                    grass, foliage, heightmap, blockEntities);
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
