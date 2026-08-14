package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.lod.LodDataSource;

import java.util.HashMap;
import java.util.Map;

/** Sparse Feature-Scheibe fuer den Generator-LOD-Pfad; allokiert keine Voxel-Sections. */
public final class LodFeatureBuffer {

    @FunctionalInterface
    public interface BlockConsumer {
        void accept(int localX, int y, int localZ, int state);
    }

    private final int targetChunkX, targetChunkZ;
    private final WorldGenerator generator;
    private final Map<Integer, Integer> blocks = new HashMap<>();
    private final Map<Long, Long> surfaces = new HashMap<>();

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
            if (tile.ifAir(i)) {
                this.setIfAir(tile.worldX(i), tile.worldY(i), tile.worldZ(i), tile.state(i));
            } else {
                this.set(tile.worldX(i), tile.worldY(i), tile.worldZ(i), tile.state(i));
            }
        }
    }

    public void forEach(BlockConsumer consumer) {
        this.blocks.forEach((key, state) -> consumer.accept(key & 31, (key >>> 10) & 511,
                (key >>> 5) & 31, state));
    }

    private boolean inTarget(int wx, int wz) {
        return (wx >> ChunkSection.SHIFT) == this.targetChunkX
                && (wz >> ChunkSection.SHIFT) == this.targetChunkZ;
    }

    private static int key(int x, int y, int z) {
        return x | (z << 5) | (y << 10);
    }

    private long surface(int wx, int wz) {
        return this.surfaces.computeIfAbsent(columnKey(wx, wz), ignored -> this.generator.sampleSurface(wx, wz));
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
