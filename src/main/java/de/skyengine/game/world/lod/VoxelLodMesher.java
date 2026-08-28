package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.PackedQuad;

import java.util.Arrays;

/** Greedy-Mesher fuer einen voll volumetrischen 32³-LOD-Knoten. */
public final class VoxelLodMesher {

    public record Material(int id, RenderLayer layer, int tintIndex, int flags, boolean occludes) {
        public Material {
            if (id < 0 || id > 0xFFFF || tintIndex < 0 || tintIndex > 0xFF
                    || flags < 0 || flags > 0xFF) throw new IllegalArgumentException("Material nicht packbar");
        }
    }

    @FunctionalInterface
    public interface MaterialResolver {
        /** axis/side erlauben getrennte Top- und Seitentexturen. */
        Material resolve(int stateId, int axis, boolean positiveSide);
    }

    @FunctionalInterface
    public interface NeighborSampler {
        /** Darf Koordinaten ausserhalb 0..31 erhalten. */
        long sample(int x, int y, int z);
    }

    public record Mesh(long[] opaque, long[] cutout, long[] translucent) {
        public int quadCount() { return opaque.length + cutout.length + translucent.length; }
        public long byteSize() { return (long) quadCount() * PackedQuad.BASE_BYTES; }
    }

    public static Mesh mesh(LodVoxelSection section, MaterialResolver materials,
                            NeighborSampler outside) {
        if (outside == null) outside = (x, y, z) -> 0L;
        LongBuffer[] out = {new LongBuffer(), new LongBuffer(), new LongBuffer()};
        long[] mask = new long[32 * 32];

        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                boolean positive = side != 0;
                for (int normal = 0; normal < 32; normal++) {
                    Arrays.fill(mask, 0L);
                    for (int b = 0; b < 32; b++) for (int a = 0; a < 32; a++) {
                        int x = axis == 0 ? normal : a;
                        int y = axis == 1 ? normal : axis == 0 ? a : b;
                        int z = axis == 2 ? normal : b;
                        long voxel = section.get(x, y, z);
                        if (LodVoxel.isEmpty(voxel)) continue;
                        int nx = x + (axis == 0 ? positive ? 1 : -1 : 0);
                        int ny = y + (axis == 1 ? positive ? 1 : -1 : 0);
                        int nz = z + (axis == 2 ? positive ? 1 : -1 : 0);
                        long neighbor = nx >= 0 && nx < 32 && ny >= 0 && ny < 32 && nz >= 0 && nz < 32
                                ? section.get(nx, ny, nz) : outside.sample(nx, ny, nz);
                        if (occludes(neighbor, materials, axis, !positive)) continue;
                        Material material = materials.resolve(LodVoxel.stateId(voxel), axis, positive);
                        if (material == null) continue;
                        /* +1 reserviert Null fuer eine leere Maske. */
                        mask[b * 32 + a] = (Integer.toUnsignedLong(material.id)
                                | (long) material.tintIndex << 16 | (long) material.flags << 24
                                | (long) material.layer.ordinal() << 32) + 1L;
                    }
                    greedy(mask, normal, axis, positive, out);
                }
            }
        }
        return new Mesh(out[RenderLayer.OPAQUE.ordinal()].toArray(),
                out[RenderLayer.CUTOUT.ordinal()].toArray(),
                out[RenderLayer.TRANSLUCENT.ordinal()].toArray());
    }

    private static boolean occludes(long voxel, MaterialResolver materials, int axis, boolean side) {
        if (LodVoxel.coverage(voxel) < 128) return false;
        Material material = materials.resolve(LodVoxel.stateId(voxel), axis, side);
        return material != null && material.occludes;
    }

    private static void greedy(long[] mask, int normal, int axis, boolean positive, LongBuffer[] out) {
        for (int b = 0; b < 32; b++) for (int a = 0; a < 32; a++) {
            long key = mask[b * 32 + a];
            if (key == 0) continue;
            int width = 1;
            while (a + width < 32 && mask[b * 32 + a + width] == key) width++;
            int height = 1;
            outer: while (b + height < 32) {
                for (int i = 0; i < width; i++) if (mask[(b + height) * 32 + a + i] != key) break outer;
                height++;
            }
            for (int db = 0; db < height; db++) {
                Arrays.fill(mask, (b + db) * 32 + a, (b + db) * 32 + a + width, 0L);
            }
            long value = key - 1L;
            int material = (int) (value & 0xFFFFL);
            int tint = (int) (value >>> 16 & 0xFFL);
            int flags = (int) (value >>> 24 & 0xFFL);
            int layer = (int) (value >>> 32 & 3L);
            int x = axis == 0 ? normal : a;
            int y = axis == 1 ? normal : axis == 0 ? a : b;
            int z = axis == 2 ? normal : b;
            out[layer].add(PackedQuad.pack(x, y, z, axis, positive, width, height,
                    0, false, material, tint, flags));
            a += width - 1;
        }
    }

    private static final class LongBuffer {
        private long[] data = new long[256];
        private int size;
        void add(long value) {
            if (this.size == this.data.length) this.data = Arrays.copyOf(this.data, this.data.length * 2);
            this.data[this.size++] = value;
        }
        long[] toArray() { return Arrays.copyOf(this.data, this.size); }
    }

    private VoxelLodMesher() {}
}
