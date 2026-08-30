package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.PackedQuad;

import java.util.Arrays;

/** Greedy-Mesher fuer einen voll volumetrischen 32³-LOD-Knoten. */
public final class VoxelLodMesher {

    public record Material(int id, RenderLayer layer, int tintIndex, int flags,
                           boolean occludes, boolean fluid) {
        public Material {
            if (id < 0 || id > 0xFFFF || tintIndex < 0 || tintIndex > 0xFF
                    || flags < 0 || flags > 0xFF) throw new IllegalArgumentException("Material nicht packbar");
        }
        public Material(int id, RenderLayer layer, int tintIndex, int flags, boolean occludes) {
            this(id, layer, tintIndex, flags, occludes, false);
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
                        if (!renderable(section.level, voxel)) continue;
                        Material material = materials.resolve(LodVoxel.stateId(voxel), axis, positive);
                        if (material == null) continue;
                        /* Fernfluide sind stabile Oberflaechen statt transparente Vollvolumen.
                           Dadurch entfallen Unterseiten, Innenlagen und unsortierte Seiten. */
                        if (material.fluid && (axis != PackedQuad.AXIS_Y || !positive)) continue;
                        int nx = x + (axis == 0 ? positive ? 1 : -1 : 0);
                        int ny = y + (axis == 1 ? positive ? 1 : -1 : 0);
                        int nz = z + (axis == 2 ? positive ? 1 : -1 : 0);
                        long neighbor = nx >= 0 && nx < 32 && ny >= 0 && ny < 32
                                && nz >= 0 && nz < 32
                                ? section.get(nx, ny, nz) : outside.sample(nx, ny, nz);
                        if (LodVoxel.stateId(neighbor) == LodVoxel.stateId(voxel)
                                && LodVoxel.coverage(neighbor) >= 128) continue;
                        if (occludes(neighbor, materials, axis, !positive)) continue;
                        /* +1 reserviert Null fuer eine leere Maske. */
                        mask[b * 32 + a] = (Integer.toUnsignedLong(material.id)
                                | (long) material.tintIndex << 16 | (long) material.flags << 24
                                | (long) material.layer.ordinal() << 32) + 1L;
                    }
                    greedy(mask, normal, axis, positive, out);
                }
            }
        }
        emitBoundarySkirts(section, materials, outside, out);
        /* Cross-Pflanzen bleiben bis L1 als echte gekreuzte Alpha-Quads erhalten. Auf noch
           groberen Stufen waere eine einzelne Pflanze bereits 4..16 Bloecke breit und wird
           zugunsten stabiler Baum-/Terrain-Silhouetten ausgelassen. */
        if (section.level <= 1) {
            for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                long voxel = section.get(x, y, z);
                if (LodVoxel.isEmpty(voxel)) continue;
                Material material = materials.resolve(LodVoxel.stateId(voxel), 3, false);
                if (material == null) continue;
                int worldX = (section.nodeX * 32 + x) << section.level;
                int worldZ = (section.nodeZ * 32 + z) << section.level;
                int random = plantHash(worldX, worldZ);
                for (int plane = 0; plane < 2; plane++) for (int side = 0; side < 2; side++) {
                    out[material.layer.ordinal()].add(PackedQuad.pack(x, y, z, PackedQuad.AXIS_CROSS,
                            side != 0, 1, 1, 0, plane != 0, material.id, random, material.flags));
                }
            }
        }
        return new Mesh(out[RenderLayer.OPAQUE.ordinal()].toArray(),
                out[RenderLayer.CUTOUT.ordinal()].toArray(),
                out[RenderLayer.TRANSLUCENT.ordinal()].toArray());
    }
    private static int plantHash(int x, int z) {
        int hash = x * 0x1F123BB5 ^ z * 0x5F356495;
        hash ^= hash >>> 16;
        return hash & 0xFF;
    }

    /**
     * Zwei Zellen tiefe, greedy zusammengefasste Übergangsstreifen unter exponierten
     * Oberflächen. Sie liegen bei gleichstufigen Nachbarn im Terrain und werden unsichtbar;
     * trifft ein feiner Knoten auf einen gröberen, schließen sie dessen Höhenquantisierung.
     * Vollständige Knotenwände und Fluidseiten werden bewusst nicht wieder eingeführt.
     */
    private static void emitBoundarySkirts(LodVoxelSection section, MaterialResolver materials,
                                            NeighborSampler outside, LongBuffer[] out) {
        for (int axis : new int[]{PackedQuad.AXIS_X, PackedQuad.AXIS_Z}) {
            for (int side = 0; side < 2; side++) {
                boolean positive = side != 0;
                int normal = positive ? 31 : 0;
                int tangent = 0;
                while (tangent < 32) {
                    Skirt skirt = boundarySkirt(section, materials, outside,
                            axis, positive, normal, tangent);
                    if (skirt == null) {
                        tangent++;
                        continue;
                    }
                    int run = 1;
                    while (tangent + run < 32 && skirt.equals(boundarySkirt(section, materials,
                            outside, axis, positive, normal, tangent + run))) run++;
                    Material material = skirt.material;
                    long quad = axis == PackedQuad.AXIS_X
                            ? PackedQuad.pack(normal, skirt.bottomY, tangent, axis, positive,
                            skirt.verticalCells, run, faceUvTransform(axis, positive), false,
                            material.id, material.tintIndex, material.flags)
                            : PackedQuad.pack(tangent, skirt.bottomY, normal, axis, positive,
                            run, skirt.verticalCells, faceUvTransform(axis, positive), false,
                            material.id, material.tintIndex, material.flags);
                    out[material.layer.ordinal()].add(quad);
                    tangent += run;
                }
            }
        }
    }

    private static Skirt boundarySkirt(LodVoxelSection section, MaterialResolver materials,
                                        NeighborSampler outside, int axis, boolean positive,
                                        int normal, int tangent) {
        int x = axis == PackedQuad.AXIS_X ? normal : tangent;
        int z = axis == PackedQuad.AXIS_Z ? normal : tangent;
        for (int y = 31; y >= 0; y--) {
            long voxel = section.get(x, y, z);
            if (!renderable(section.level, voxel)) continue;
            Material material = materials.resolve(LodVoxel.stateId(voxel), axis, positive);
            if (material == null) continue;
            if (material.fluid) return null;
            long above = y < 31 ? section.get(x, y + 1, z) : outside.sample(x, 32, z);
            if (faceSuppressed(voxel, above, materials, PackedQuad.AXIS_Y, false)) return null;
            int nx = x + (axis == PackedQuad.AXIS_X ? positive ? 1 : -1 : 0);
            int nz = z + (axis == PackedQuad.AXIS_Z ? positive ? 1 : -1 : 0);
            long across = outside.sample(nx, y, nz);
            /* Ist die normale Seitenfläche ohnehin sichtbar, braucht sie keinen Skirt. */
            if (!faceSuppressed(voxel, across, materials, axis, !positive)) return null;
            int bottom = Math.max(0, y - 1);
            return new Skirt(bottom, y - bottom + 1, material);
        }
        return null;
    }

    private static boolean faceSuppressed(long voxel, long neighbor, MaterialResolver materials,
                                          int axis, boolean neighborSide) {
        return LodVoxel.stateId(neighbor) == LodVoxel.stateId(voxel)
                && LodVoxel.coverage(neighbor) >= 128
                || occludes(neighbor, materials, axis, neighborSide);
    }

    private record Skirt(int bottomY, int verticalCells, Material material) {}

    private static boolean occludes(long voxel, MaterialResolver materials, int axis, boolean side) {
        if (LodVoxel.coverage(voxel) < 128) return false;
        Material material = materials.resolve(LodVoxel.stateId(voxel), axis, side);
        return material != null && material.occludes;
    }

    private static boolean renderable(int level, long voxel) {
        int coverage = LodVoxel.coverage(voxel);
        if (coverage == 0) return false;
        /* L1 ist der kompakte Detailstrom: dort bleiben auch einzelne Stämme, Blätter und
           dünne Bauwerke sichtbar. Erst ab L2 gilt die Mehrheitsbelegung, die eine einzelne
           Featurezelle nicht zu einem 4..16 Bloecke grossen Würfel aufblasen lässt. */
        if (level <= 1) return true;
        return coverage >= 128;
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
                    faceUvTransform(axis, positive), false, material, tint, flags));
            a += width - 1;
        }
    }

    /**
     * Entspricht exakt den sechs UV-Konventionen aus {@code BlockModels.extentUv}. Die
     * Geometrieachsen des kompakten Quads sind je Face verschieden; transform=0 fuer alle
     * Seiten dreht insbesondere X-Faces um 90 Grad und stellt Seiten mit gerichteter Textur
     * auf den Kopf.
     */
    static int faceUvTransform(int axis, boolean positive) {
        return switch (axis) {
            case PackedQuad.AXIS_X -> positive ? 7 : 1;
            case PackedQuad.AXIS_Y -> positive ? 0 : 6;
            case PackedQuad.AXIS_Z -> positive ? 6 : 2;
            default -> 0;
        };
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
