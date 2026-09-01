package de.skyengine.game.world.chunk;

import java.util.ArrayList;
import java.util.List;

/** CPU-side view of the compact full-cube streams, mirroring the vertex shader in tests. */
final class CompactTerrainTestView {

    private static final int[][] CORNER_S = {
            {0,0,1,1}, {0,1,1,0}, {1,0,0,1}, {0,1,1,0}, {0,0,1,1}, {0,0,1,1}
    };
    private static final int[][] CORNER_T = {
            {0,1,1,0}, {0,0,1,1}, {0,0,1,1}, {0,0,1,1}, {0,1,1,0}, {1,0,0,1}
    };
    private static final int[][] TRIANGLES_NORMAL = {{0,1,2}, {2,3,0}};
    private static final int[][] TRIANGLES_FLIPPED = {{1,2,3}, {3,0,1}};
    private static final double EPSILON = 1.0e-6;

    static List<Quad> quads(ChunkMesher.MeshData mesh) {
        List<Quad> result = new ArrayList<>();
        for (int mode = 0; mode < mesh.compactGeometry.length; mode++) {
            int[] geometry = mesh.compactGeometry[mode];
            if (geometry == null) continue;
            int[] shading = mesh.compactShading[mode];
            for (int offset = 0, quad = 0; offset < geometry.length; offset += 2, quad++) {
                int g0 = geometry[offset];
                int[] lights = new int[4];
                int[] ao = new int[4];
                if (mode == PackedTerrainQuad.SHADING_STANDARD) {
                    for (int corner = 0; corner < 4; corner++) {
                        lights[corner] = VertexLight.fromLevels(15, 0);
                        ao[corner] = 3;
                    }
                } else if (mode == PackedTerrainQuad.SHADING_UNIFORM) {
                    int word = shading[quad];
                    int light = decodedLight(word);
                    for (int corner = 0; corner < 4; corner++) {
                        lights[corner] = light;
                        ao[corner] = PackedTerrainQuad.ao(word);
                    }
                } else {
                    for (int corner = 0; corner < 4; corner++) {
                        int word = shading[quad * 4 + corner];
                        lights[corner] = decodedLight(word);
                        ao[corner] = PackedTerrainQuad.ao(word);
                    }
                }
                result.add(new Quad(g0, geometry[offset + 1], lights, ao));
            }
        }
        return result;
    }

    static List<Integer> lightsAt(ChunkMesher.MeshData mesh, double x, double y, double z) {
        List<Integer> result = new ArrayList<>();
        for (Quad quad : quads(mesh)) {
            Integer light = quad.sampleLight(x, y, z);
            if (light != null) result.add(light);
        }
        return result;
    }

    private static int decodedLight(int word) {
        int sky = PackedTerrainQuad.sampleSumToByteLight(PackedTerrainQuad.skySum(word));
        int block = PackedTerrainQuad.sampleSumToByteLight(PackedTerrainQuad.redSum(word));
        return sky | block << 8;
    }

    record Quad(int geometry, int material, int[] lights, int[] ao) {

        int axis() { return PackedTerrainQuad.axis(this.geometry); }
        boolean positive() { return PackedTerrainQuad.positive(this.geometry); }
        int face() {
            return this.axis() == 1 ? (this.positive() ? 0 : 1)
                    : this.axis() == 2 ? (this.positive() ? 3 : 2) : (this.positive() ? 5 : 4);
        }
        int materialHandle() { return PackedTerrainQuad.materialId(this.material); }
        double plane() {
            int base = this.axis() == 0 ? PackedTerrainQuad.x(this.geometry)
                    : this.axis() == 1 ? PackedTerrainQuad.y(this.geometry)
                    : PackedTerrainQuad.z(this.geometry);
            return base + (this.positive() ? 1 : 0);
        }
        double minS() {
            return this.axis() == 0 ? PackedTerrainQuad.y(this.geometry) : PackedTerrainQuad.x(this.geometry);
        }
        double minT() {
            return this.axis() == 2 ? PackedTerrainQuad.y(this.geometry) : PackedTerrainQuad.z(this.geometry);
        }
        double maxS() { return this.minS() + PackedTerrainQuad.width(this.geometry); }
        double maxT() { return this.minT() + PackedTerrainQuad.height(this.geometry); }

        Integer sampleLight(double x, double y, double z) {
            double normal = this.axis() == 0 ? x : this.axis() == 1 ? y : z;
            double s = this.axis() == 0 ? y : x;
            double t = this.axis() == 2 ? y : z;
            if (Math.abs(normal - this.plane()) > EPSILON || s < this.minS() - EPSILON
                    || s > this.maxS() + EPSILON || t < this.minT() - EPSILON
                    || t > this.maxT() + EPSILON) return null;
            double localS = (s - this.minS()) / PackedTerrainQuad.width(this.geometry);
            double localT = (t - this.minT()) / PackedTerrainQuad.height(this.geometry);
            int sky = interpolateChannel(localS, localT, false);
            int block = interpolateChannel(localS, localT, true);
            return sky | block << 8;
        }

        List<Integer> cornerColors() {
            List<Integer> result = new ArrayList<>(4);
            float brightness = this.face() == 0 ? 1F : this.face() == 1 ? 0.5F
                    : this.face() <= 3 ? 0.8F : 0.6F;
            for (int value : this.ao) {
                int channel = Math.round(255F * brightness * (0.4F + value * 0.2F));
                result.add(channel << 16 | channel << 8 | channel);
            }
            return result;
        }

        private int interpolateChannel(double s, double t, boolean block) {
            int[][] triangles = PackedTerrainQuad.diagonalFlip(this.geometry)
                    ? TRIANGLES_FLIPPED : TRIANGLES_NORMAL;
            int face = this.face();
            for (int[] triangle : triangles) {
                double x0 = CORNER_S[face][triangle[0]], y0 = CORNER_T[face][triangle[0]];
                double x1 = CORNER_S[face][triangle[1]], y1 = CORNER_T[face][triangle[1]];
                double x2 = CORNER_S[face][triangle[2]], y2 = CORNER_T[face][triangle[2]];
                double denominator = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
                double a = ((y1 - y2) * (s - x2) + (x2 - x1) * (t - y2)) / denominator;
                double b = ((y2 - y0) * (s - x2) + (x0 - x2) * (t - y2)) / denominator;
                double c = 1.0 - a - b;
                if (a < -EPSILON || b < -EPSILON || c < -EPSILON) continue;
                int v0 = channel(this.lights[triangle[0]], block);
                int v1 = channel(this.lights[triangle[1]], block);
                int v2 = channel(this.lights[triangle[2]], block);
                return (int) Math.round(a * v0 + b * v1 + c * v2);
            }
            throw new AssertionError("point inside quad did not hit either triangle");
        }

        private static int channel(int light, boolean block) {
            return block ? VertexLight.block(light) : VertexLight.sky(light);
        }
    }

    private CompactTerrainTestView() {}
}
