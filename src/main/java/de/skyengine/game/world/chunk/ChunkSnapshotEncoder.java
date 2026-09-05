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
import de.skyengine.shared.world.ImmutableChunkColumnData;
import de.skyengine.shared.world.ImmutableChunkSectionData;
import de.skyengine.shared.world.ImmutableIntArray;
import de.skyengine.graphics.PerformanceProfiler;

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

    /** Mutable server objects captured under the chunk lock, but not encoded there. */
    private record CapturedBlockEntity(int localX, int y, int localZ, String typeId, DataTag tag) { }
    private record FrozenColumn(long revision, List<ChunkSectionSnapshot> sections,
                                ImmutableIntArray biomeIds, ImmutableIntArray grass,
                                ImmutableIntArray foliage, ImmutableIntArray heightmap,
                                List<CapturedBlockEntity> blockEntities) { }

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

        return capture(dimension, chunk, generator, null);
    }

    /** Reuses immutable metadata and unchanged section revisions from the preceding snapshot. */
    public static ChunkColumnSnapshot encode(String dimension, Chunk chunk, WorldGenerator generator,
                                             ChunkColumnSnapshot previous) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(generator, "generator");
        return capture(dimension, chunk, generator, previous);
    }

    /**
     * Copies mutable chunk-owned data exactly once while holding the read lock. The immutable
     * snapshot constructors perform that ownership copy; pre-cloning the four column arrays
     * used to copy another ~17 KiB per streamed column for no consistency benefit.
     */
    private static ChunkColumnSnapshot capture(String dimension, Chunk chunk,
                                               WorldGenerator generator,
                                               ChunkColumnSnapshot previous) {
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
        // Generator lookups are pure and may be relatively expensive. Prepare fallbacks before
        // acquiring world ownership; normally generated/LIT chunks already own these arrays.
        int[] generatedBiomeIds = chunk.biomeIds == null ? biomeIds(generator, baseX, baseZ) : null;
        int[] generatedGrass = chunk.grassTintCorners == null
                ? tintCorners(generator, baseX, baseZ, true) : null;
        int[] generatedFoliage = chunk.foliageTintCorners == null
                ? tintCorners(generator, baseX, baseZ, false) : null;
        int[] generatedHeightmap = chunk.heightmap == null ? stableFallbackHeightmap(chunk) : null;

        /* Freezing palette/bit-storage references is a tiny structural mutation. Use the
           column write lock for that revision swap; no block scan, encoding or compression is
           performed while ownership is held. */
        PerformanceProfiler profiler = PerformanceProfiler.get();
        long freezeStarted = profiler.begin();
        long lockStarted = profiler.begin();
        chunk.writeLock().lock();
        profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_SNAPSHOT_LOCK_WAIT, lockStarted);
        long lockHeld = profiler.begin();
        FrozenColumn frozenColumn;
        try {
            List<ChunkSectionSnapshot> sections = new ArrayList<>();
            for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                ChunkSection section = chunk.getSection(sectionY);
                if (section == null || section.isEmpty()) continue;
                PalettedContainer container = section.container();
                PalettedContainer.FrozenData frozen = container.freezeData();
                var skyLight = chunk.light.snapshotSection(sectionY);
                var blockLight = chunk.blockLight.snapshotSection(sectionY);
                ChunkSectionSnapshot old = section(previous, sectionY);
                if (old != null && old.nonAir() == frozen.nonAir()
                        && old.bitsPerEntry() == frozen.bitsPerEntry()
                        && old.paletteData().sharesStorageWith(frozen.palette())
                        && old.packedPaletteData().sharesStorageWith(frozen.packedIndices())
                        && old.skyLight() == skyLight && old.blockLight() == blockLight) {
                    sections.add(old);
                } else {
                    sections.add(ImmutableChunkSectionData.shared(sectionY, frozen.nonAir(),
                            frozen.palette(), frozen.bitsPerEntry(), frozen.packedIndices(),
                            skyLight, blockLight));
                }
            }

            int[] biomeIds = chunk.biomeIds == null ? generatedBiomeIds : chunk.biomeIds;
            int[] grass = chunk.grassTintCorners == null ? generatedGrass : chunk.grassTintCorners;
            int[] foliage = chunk.foliageTintCorners == null ? generatedFoliage : chunk.foliageTintCorners;
            int[] heightmap = chunk.heightmap == null ? generatedHeightmap : chunk.heightmap;
            boolean reusableMetadata = previous != null
                    && previous.dimension().equals(dimension)
                    && previous.chunkX() == chunk.chunkX && previous.chunkZ() == chunk.chunkZ;
            frozenColumn = new FrozenColumn(chunk.modificationEpoch(), List.copyOf(sections),
                    reusableMetadata ? previous.biomeData() : ImmutableIntArray.copyOf(biomeIds),
                    reusableMetadata ? previous.grassTintData() : ImmutableIntArray.copyOf(grass),
                    reusableMetadata ? previous.foliageTintData() : ImmutableIntArray.copyOf(foliage),
                    reusableMetadata && previous.heightmapData().contentEquals(heightmap)
                            ? previous.heightmapData() : ImmutableIntArray.copyOf(heightmap),
                    captureBlockEntities(chunk));
        } finally {
            chunk.writeLock().unlock();
            profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_SNAPSHOT_LOCK_HOLD, lockHeld);
        }

        // Binary DataTag encoding is deliberately outside the world-ownership lock. The tag is
        // already a detached capture, so this work cannot observe a half-mutated block entity.
        List<BlockEntitySnapshot> blockEntities = encodeBlockEntities(
                frozenColumn.blockEntities(), previous);
        ChunkColumnSnapshot result = ImmutableChunkColumnData.shared(dimension, chunk.chunkX,
                chunk.chunkZ, frozenColumn.revision(), frozenColumn.sections(),
                frozenColumn.biomeIds(), frozenColumn.grass(), frozenColumn.foliage(),
                frozenColumn.heightmap(), blockEntities);
        profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_SNAPSHOT_FREEZE, freezeStarted);
        return result;
    }

    private static ChunkSectionSnapshot section(ChunkColumnSnapshot snapshot, int sectionY) {
        if (snapshot == null) return null;
        for (ChunkSectionSnapshot section : snapshot.sections()) {
            if (section.sectionY() == sectionY) return section;
        }
        return null;
    }

    private static List<CapturedBlockEntity> captureBlockEntities(Chunk chunk) {
        if (chunk.blockEntities().isEmpty()) return List.of();
        List<CapturedBlockEntity> result = new ArrayList<>(chunk.blockEntities().size());
        for (BlockEntity entity : chunk.blockEntities()) {
            var typeId = Registries.BLOCK_ENTITY.idOf(entity.getType());
            if (typeId == null) continue;
            DataTag tag = new DataTag();
            entity.saveNetwork(tag);
            var pos = entity.getPos();
            result.add(new CapturedBlockEntity(pos.x() & ChunkSection.MASK, pos.y(),
                    pos.z() & ChunkSection.MASK, typeId.toString(), tag));
        }
        return List.copyOf(result);
    }

    private static List<BlockEntitySnapshot> encodeBlockEntities(
            List<CapturedBlockEntity> captured, ChunkColumnSnapshot previous) {
        if (captured.isEmpty()) return List.of();
        List<BlockEntitySnapshot> result = new ArrayList<>(captured.size());
        for (CapturedBlockEntity entity : captured) {
            byte[] bytes = encodeTag(entity.tag());
            BlockEntitySnapshot old = blockEntity(previous, entity.localX(), entity.y(), entity.localZ());
            if (old != null && old.typeId().equals(entity.typeId())
                    && old.dataPayload().contentEquals(bytes)) {
                result.add(old);
            } else {
                result.add(BlockEntitySnapshot.takeOwnership(entity.localX(), entity.y(),
                        entity.localZ(), entity.typeId(), bytes));
            }
        }
        return List.copyOf(result);
    }

    private static BlockEntitySnapshot blockEntity(
            ChunkColumnSnapshot snapshot, int localX, int y, int localZ) {
        if (snapshot == null) return null;
        for (BlockEntitySnapshot entity : snapshot.blockEntities()) {
            if (entity.localX() == localX && entity.y() == y && entity.localZ() == localZ) return entity;
        }
        return null;
    }

    /** Serializes one dirty block entity without rebuilding its complete chunk snapshot. */
    public static BlockEntitySnapshot encodeBlockEntity(BlockEntity entity) {
        Objects.requireNonNull(entity, "entity");
        var typeId = Registries.BLOCK_ENTITY.idOf(entity.getType());
        if (typeId == null) return null;
        DataTag tag = new DataTag();
        entity.saveNetwork(tag);
        byte[] bytes = encodeTag(tag);
        var pos = entity.getPos();
        return BlockEntitySnapshot.takeOwnership(pos.x() & ChunkSection.MASK, pos.y(),
                pos.z() & ChunkSection.MASK, typeId.toString(), bytes);
    }

    private static byte[] encodeTag(DataTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            DataTagIO.write(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException("BlockEntity-Netzwerksnapshot fehlgeschlagen", failure);
        }
    }

    private static int[] biomeIds(WorldGenerator generator, int baseX, int baseZ) {
        int[] result = new int[COLUMN_SIZE];
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                result[(z << ChunkSection.SHIFT) | x] = generator.biomeAt(baseX + x, baseZ + z).id;
            }
        }
        return result;
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

    /**
     * Compatibility fallback for synthetic/pre-lighting chunks. Productive replication only
     * snapshots LIT chunks and therefore already has a heightmap. Keep the exceptional scan
     * outside the short structural freeze lock and retry if its published mutation epoch moves.
     */
    private static int[] stableFallbackHeightmap(Chunk chunk) {
        int[] result;
        long before;
        do {
            before = chunk.modificationEpoch();
            result = rebuildHeightmap(chunk);
        } while (before != chunk.modificationEpoch());
        return result;
    }
}
