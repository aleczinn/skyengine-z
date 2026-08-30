package de.skyengine.game.world.lod;

import de.skyengine.game.world.chunk.ChunkSection;

import java.util.Arrays;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

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
    private long[] voxels;
    private long[] palette;
    private long[] packedIndices;
    private int indexBits;
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

    /** Leerer, bereits kompakter Knoten ohne temporaere 256-KiB-Rohtabelle. */
    public static LodVoxelSection empty(int nodeX, int nodeY, int nodeZ, int level,
                                        Completeness completeness) {
        if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("LOD-Level: " + level);
        LodVoxelSection result = new LodVoxelSection(nodeX, nodeY, nodeZ, level,
                completeness, new long[0], true);
        result.palette = new long[]{0L};
        result.packedIndices = new long[0];
        return result;
    }

    private LodVoxelSection(int nodeX, int nodeY, int nodeZ, int level,
                            Completeness completeness, long[] ignored, boolean compactEmpty) {
        this.nodeX = nodeX;
        this.nodeY = nodeY;
        this.nodeZ = nodeZ;
        this.level = level;
        this.completeness = completeness;
        this.voxels = null;
    }

    public long get(int x, int y, int z) { return this.getByIndex(index(x, y, z)); }
    public void set(int x, int y, int z, long voxel) {
        this.ensureMutable();
        this.voxels[index(x, y, z)] = voxel;
    }
    public long[] copyVoxels() {
        if (this.voxels != null) return Arrays.copyOf(this.voxels, this.voxels.length);
        long[] copy = new long[VOLUME];
        for (int i = 0; i < copy.length; i++) copy[i] = this.getByIndex(i);
        return copy;
    }

    long getByIndex(int index) {
        if (this.voxels != null) return this.voxels[index];
        if (this.indexBits == 0) return this.palette[0];
        long bit = (long) index * this.indexBits;
        int word = (int) (bit >>> 6), shift = (int) (bit & 63L);
        long value = this.packedIndices[word] >>> shift;
        if (shift + this.indexBits > 64) value |= this.packedIndices[word + 1] << (64 - shift);
        int paletteIndex = (int) (value & (1L << this.indexBits) - 1L);
        return this.palette[paletteIndex];
    }

    /**
     * Friert den Knoten in eine lokale Palette plus bitgepackte Indizes ein, sofern das
     * tatsaechlich kleiner als die rohe long-Tabelle ist. Ein spaeteres set() expandiert
     * transparent wieder; normale LOD-Knoten bleiben nach ihrer Publikation unveraendert.
     */
    public void compact() {
        if (this.voxels == null) return;
        /* Mehr als 256 verschiedene Zellen lohnen die Palette fuer diesen Hotpath kaum. Ein
           primitives Open-Addressing-Set vermeidet vor allem 32k Long/Integer-Boxobjekte pro
           gleichzeitigem Build. */
        long[] keys = new long[512];
        short[] slots = new short[512]; // 0 = leer, Palettenindex + 1
        long[] values = new long[256];
        int count = 0;
        for (long voxel : this.voxels) {
            int slot = hash(voxel) & 511;
            while (slots[slot] != 0 && keys[slot] != voxel) slot = slot + 1 & 511;
            if (slots[slot] != 0) continue;
            if (count == values.length) return;
            keys[slot] = voxel;
            slots[slot] = (short) (count + 1);
            values[count++] = voxel;
        }
        int bits = count <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
        int words = (int) (((long) VOLUME * bits + 63L) >>> 6);
        if ((long) count * Long.BYTES + (long) words * Long.BYTES >= (long) VOLUME * Long.BYTES) return;
        long[] indices = new long[words];
        if (bits != 0) {
            for (int i = 0; i < VOLUME; i++) {
                long voxel = this.voxels[i];
                int slot = hash(voxel) & 511;
                while (slots[slot] != 0 && keys[slot] != voxel) slot = slot + 1 & 511;
                long id = slots[slot] - 1L;
                long bit = (long) i * bits;
                int word = (int) (bit >>> 6), shift = (int) (bit & 63L);
                indices[word] |= id << shift;
                if (shift + bits > 64) indices[word + 1] |= id >>> (64 - shift);
            }
        }
        this.palette = Arrays.copyOf(values, count);
        this.packedIndices = indices;
        this.indexBits = bits;
        this.voxels = null;
    }

    private static int hash(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return (int) value;
    }

    public long estimatedBytes() {
        return this.voxels != null ? (long) this.voxels.length * Long.BYTES
                : (long) this.palette.length * Long.BYTES + (long) this.packedIndices.length * Long.BYTES;
    }

    /** Kompaktes Cacheformat ohne temporaere 256-KiB-Rohtabelle fuer palettierte Knoten. */
    void writeStorage(DataOutput out) throws IOException {
        if (this.voxels != null) {
            out.writeByte(0);
            for (long voxel : this.voxels) out.writeLong(voxel);
            return;
        }
        out.writeByte(1);
        out.writeByte(this.indexBits);
        out.writeShort(this.palette.length);
        for (long value : this.palette) out.writeLong(value);
        out.writeInt(this.packedIndices.length);
        for (long word : this.packedIndices) out.writeLong(word);
    }

    static LodVoxelSection readStorage(DataInput in, int nodeX, int nodeY, int nodeZ, int level,
                                       Completeness completeness) throws IOException {
        int mode = in.readUnsignedByte();
        if (mode == 0) {
            long[] voxels = new long[VOLUME];
            for (int i = 0; i < voxels.length; i++) voxels[i] = in.readLong();
            LodVoxelSection result = new LodVoxelSection(nodeX, nodeY, nodeZ, level,
                    completeness, voxels);
            result.compact();
            return result;
        }
        if (mode != 1) throw new IOException("Unbekannte LOD-Speicherform: " + mode);
        int bits = in.readUnsignedByte();
        int paletteSize = in.readUnsignedShort();
        if (paletteSize < 1 || paletteSize > 256 || bits < 0 || bits > 8) {
            throw new IOException("Ungueltige LOD-Palette");
        }
        long[] palette = new long[paletteSize];
        for (int i = 0; i < palette.length; i++) palette[i] = in.readLong();
        int expectedWords = (int) (((long) VOLUME * bits + 63L) >>> 6);
        int words = in.readInt();
        if (words != expectedWords) throw new IOException("Ungueltige LOD-Indextabelle");
        long[] indices = new long[words];
        for (int i = 0; i < indices.length; i++) indices[i] = in.readLong();
        LodVoxelSection result = empty(nodeX, nodeY, nodeZ, level, completeness);
        result.palette = palette;
        result.packedIndices = indices;
        result.indexBits = bits;
        return result;
    }
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

    private void ensureMutable() {
        if (this.voxels != null) return;
        this.voxels = this.copyVoxels();
        this.palette = null;
        this.packedIndices = null;
        this.indexBits = 0;
    }
}
