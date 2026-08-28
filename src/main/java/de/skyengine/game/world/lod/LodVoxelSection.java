package de.skyengine.game.world.lod;

import de.skyengine.game.world.chunk.ChunkSection;

import java.util.Arrays;

/**
 * Ein 32x32x32-Knoten der volumetrischen LOD-Hierarchie. L0-Zellen sind Bloecke; bei L4 ist
 * eine Zelle 16 Bloecke und ein Knoten deckt 512 Bloecke pro Achse ab.
 */
public final class LodVoxelSection {

    public static final int SIZE = ChunkSection.SIZE;
    public static final int VOLUME = ChunkSection.VOLUME;
    public static final int MAX_LEVEL = 4;

    public enum Completeness { PROVISIONAL, CANONICAL }

    public final int nodeX, nodeY, nodeZ, level;
    private final long[] voxels;
    private Completeness completeness;

    public LodVoxelSection(int nodeX, int nodeY, int nodeZ, int level, Completeness completeness) {
        this(nodeX, nodeY, nodeZ, level, completeness, new long[VOLUME]);
    }

    public LodVoxelSection(int nodeX, int nodeY, int nodeZ, int level,
                           Completeness completeness, long[] voxels) {
        if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("LOD-Level: " + level);
        if (voxels.length != VOLUME) throw new IllegalArgumentException("LOD-Knoten braucht " + VOLUME + " Zellen");
        this.nodeX = nodeX;
        this.nodeY = nodeY;
        this.nodeZ = nodeZ;
        this.level = level;
        this.completeness = completeness;
        this.voxels = voxels;
    }

    public long get(int x, int y, int z) { return this.voxels[index(x, y, z)]; }
    public void set(int x, int y, int z, long voxel) { this.voxels[index(x, y, z)] = voxel; }
    public long[] copyVoxels() { return Arrays.copyOf(this.voxels, this.voxels.length); }
    long[] voxels() { return this.voxels; }
    public Completeness completeness() { return this.completeness; }
    public void markCanonical() { this.completeness = Completeness.CANONICAL; }
    public int cellSize() { return 1 << this.level; }
    public int extent() { return SIZE << this.level; }

    public static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SIZE || y >= SIZE || z >= SIZE) {
            throw new IndexOutOfBoundsException(x + "," + y + "," + z);
        }
        return (y << 10) | (z << 5) | x;
    }
}
