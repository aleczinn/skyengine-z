package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.utils.collect.LongIntMap;
import de.skyengine.utils.collect.LongLongMap;

import java.util.Arrays;
import java.util.Random;

/** Ungefilterte, geordnete Feature-Schreibbefehle genau eines Quell-Chunks. */
final class LodFeatureTile implements FeatureContext {

    private final int sourceMinX, sourceMinZ;
    private final WorldGenerator generator;
    private LongIntMap solidHeights = new LongIntMap(64);
    private LongLongMap surfaces = new LongLongMap(64);
    private long[] positions = new long[128];
    private int[] yAndMode = new int[128];
    private int[] states = new int[128];
    private int size;
    private Random random;

    LodFeatureTile(int sourceChunkX, int sourceChunkZ, WorldGenerator generator) {
        this.sourceMinX = sourceChunkX << ChunkSection.SHIFT;
        this.sourceMinZ = sourceChunkZ << ChunkSection.SHIFT;
        this.generator = generator;
    }

    void begin(Random random) {
        this.random = random;
    }

    void freeze() {
        this.positions = Arrays.copyOf(this.positions, this.size);
        this.yAndMode = Arrays.copyOf(this.yAndMode, this.size);
        this.states = Arrays.copyOf(this.states, this.size);
        this.solidHeights = null;
        this.surfaces = null;
        this.random = null;
    }

    int size() { return this.size; }
    int worldX(int index) { return (int) (this.positions[index] >> 32); }
    int worldZ(int index) { return (int) this.positions[index]; }
    int worldY(int index) { return this.yAndMode[index] >>> 1; }
    boolean ifAir(int index) { return (this.yAndMode[index] & 1) != 0; }
    int state(int index) { return this.states[index]; }
    long estimatedBytes() { return 64L + 16L * this.size; }

    @Override public Random random() { return this.random; }
    @Override public int sourceMinX() { return this.sourceMinX; }
    @Override public int sourceMinZ() { return this.sourceMinZ; }
    @Override public int surfaceHeight(int wx, int wz) {
        long key = columnKey(wx, wz);
        int cached = this.solidHeights.getOrDefault(key, Integer.MIN_VALUE);
        if (cached != Integer.MIN_VALUE) return cached;
        int height = this.generator.surfaceSolidHeight(wx, wz);
        this.solidHeights.put(key, height);
        return height;
    }
    @Override public int surfaceBlock(int wx, int wz) {
        long key = columnKey(wx, wz);
        long cached = this.surfaces.getOrDefault(key, Long.MIN_VALUE);
        if (cached == Long.MIN_VALUE) {
            cached = this.generator.sampleSurface(wx, wz);
            this.surfaces.put(key, cached);
        }
        return LodDataSource.block(cached);
    }
    @Override public Biome biome(int wx, int wz) { return this.generator.biomeAt(wx, wz); }

    @Override public void set(int wx, int wy, int wz, int block) {
        this.add(wx, wy, wz, block, false);
    }

    @Override public void setIfAir(int wx, int wy, int wz, int block) {
        this.add(wx, wy, wz, block, true);
    }

    private void add(int wx, int wy, int wz, int block, boolean ifAir) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return;
        if (this.size == this.positions.length) {
            int next = this.positions.length << 1;
            this.positions = Arrays.copyOf(this.positions, next);
            this.yAndMode = Arrays.copyOf(this.yAndMode, next);
            this.states = Arrays.copyOf(this.states, next);
        }
        this.positions[this.size] = ((long) wx << 32) | (wz & 0xFFFFFFFFL);
        this.yAndMode[this.size] = (wy << 1) | (ifAir ? 1 : 0);
        this.states[this.size] = block;
        this.size++;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
