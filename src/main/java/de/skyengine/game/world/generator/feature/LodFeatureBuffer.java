package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.utils.collect.LongIntMap;
import de.skyengine.utils.collect.LongLongMap;


/** Sparse Feature-Scheibe fuer den Generator-LOD-Pfad; allokiert keine Voxel-Sections. */
public final class LodFeatureBuffer {

    @FunctionalInterface
    public interface BlockConsumer {
        void accept(int localX, int y, int localZ, int state);
    }

    private final int targetChunkX, targetChunkZ;
    private final WorldGenerator generator;
    private final LongIntMap blocks = new LongIntMap(256);
    private final LongIntMap supports = new LongIntMap(16);
    private final LongLongMap surfaces = new LongLongMap(64);

    LodFeatureBuffer(int targetChunkX, int targetChunkZ, WorldGenerator generator) {
        this.targetChunkX = targetChunkX;
        this.targetChunkZ = targetChunkZ;
        this.generator = generator;
    }

    void set(int wx, int wy, int wz, int block) {
        if (!this.inTarget(wx, wz) || wy < 0 || wy >= Chunk.HEIGHT) return;
        this.blocks.put(key(wx & ChunkSection.MASK, wy, wz & ChunkSection.MASK), block);
    }

    void setIfAir(int wx, int wy, int wz, int block) {
        if (!this.inTarget(wx, wz) || wy < 0 || wy >= Chunk.HEIGHT) return;
        int key = key(wx & ChunkSection.MASK, wy, wz & ChunkSection.MASK);
        if (this.blocks.containsKey(key)) return;
        long surface = this.surface(wx, wz);
        if (wy <= LodDataSource.height(surface) && LodDataSource.block(surface) != Blocks.AIR) return;
        this.blocks.put(key, block);
    }

    void apply(LodFeatureTile tile) {
        for (int i = 0; i < tile.size(); i++) {
            this.apply(tile, i);
        }
        for (int i = 0; i < tile.supportSize(); i++) this.applySupport(tile, i);
    }

    void apply(LodFeatureTile tile, int index) {
        if (tile.ifAir(index)) {
            this.setIfAir(tile.worldX(index), tile.worldY(index), tile.worldZ(index), tile.state(index));
        } else {
            this.set(tile.worldX(index), tile.worldY(index), tile.worldZ(index), tile.state(index));
        }
    }

    void applySupport(LodFeatureTile tile, int index) {
        int wx = tile.supportWorldX(index), wz = tile.supportWorldZ(index);
        if (!this.inTarget(wx, wz)) return;
        int wy = tile.supportWorldY(index);
        this.supports.put(key(wx & ChunkSection.MASK, wy, wz & ChunkSection.MASK), 1);
    }

    public boolean isSupport(int localX, int y, int localZ) {
        return this.supports.containsKey(key(localX, y, localZ));
    }

    public void forEach(BlockConsumer consumer) {
        for (int i = 0; i < this.blocks.tableSize(); i++) {
            if (!this.blocks.usedAt(i)) continue;
            int key = (int) this.blocks.keyAt(i);
            consumer.accept(key & 31, (key >>> 10) & 511, (key >>> 5) & 31,
                    this.blocks.valueAt(i));
        }
    }

    private boolean inTarget(int wx, int wz) {
        return (wx >> ChunkSection.SHIFT) == this.targetChunkX
                && (wz >> ChunkSection.SHIFT) == this.targetChunkZ;
    }

    private static int key(int x, int y, int z) {
        return x | (z << 5) | (y << 10);
    }

    private long surface(int wx, int wz) {
        long key = columnKey(wx, wz);
        long cached = this.surfaces.getOrDefault(key, Long.MIN_VALUE);
        if (cached != Long.MIN_VALUE) return cached;
        long surface = this.generator.sampleSurface(wx, wz);
        this.surfaces.put(key, surface);
        return surface;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
