package de.skyengine.game.world.block.model;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal OBJ loader for Mekanism's transmitter model. The original object groups are named
 * {@code northNONE}, {@code northNORMAL}, ... and are selected directly from connection state.
 */
public final class MekanismTransmitterModel {
    private static final String MODEL = "game/models/block/mekanism/transmitter_small.obj.mek";
    private static final String CENTER = "game/textures/block/mekanism/multipart/basic_universal_cable.png";
    private static final String SIDE = "game/textures/block/mekanism/multipart/basic_universal_cable_vertical.png";
    private static final Map<String, Group> GROUPS = load();

    private record Vertex(float x, float y, float z) {}
    private record Uv(float u, float v) {}
    private record Group(BakedQuad[] quads, AABB bounds) {}

    public static ModelLoader.Baked bake(BlockState state) {
        List<BakedQuad[]> parts = new ArrayList<>(6);
        List<AABB> boxes = new ArrayList<>(7);
        boxes.add(new AABB(5 / 16D, 5 / 16D, 5 / 16D, 11 / 16D, 11 / 16D, 11 / 16D));
        for (Direction direction : Direction.sharedValues()) {
            boolean connected = property(state, direction.name().toLowerCase(Locale.ROOT));
            Group group = GROUPS.get(direction.name().toLowerCase(Locale.ROOT)
                    + (connected ? "NORMAL" : "NONE"));
            if (group == null) throw new IllegalStateException("Missing transmitter group for " + direction);
            parts.add(group.quads);
            if (connected && group.bounds != null) boxes.add(group.bounds.copy());
        }
        BakedQuad[] quads = BlockModels.concat(parts.toArray(new BakedQuad[0][]));
        return new ModelLoader.Baked(quads, boxes.toArray(new AABB[0]), BlockTextures.layerOf(CENTER));
    }

    private static boolean property(BlockState state, String name) {
        for (Map.Entry<Property<?>, Object> entry : state.getValues().entrySet()) {
            if (entry.getKey().getName().equals(name)) return Boolean.TRUE.equals(entry.getValue());
        }
        return false;
    }

    private static Map<String, Group> load() {
        List<Vertex> vertices = new ArrayList<>();
        List<Uv> uvs = new ArrayList<>();
        Map<String, List<BakedQuad>> groups = new HashMap<>();
        Map<String, Bounds> bounds = new HashMap<>();
        String group = null;
        String material = "None";
        for (String raw : new FileHandle(MODEL, FileType.RESOURCE).readList()) {
            String line = raw.trim();
            if (line.startsWith("o ")) {
                group = line.substring(2).trim();
                groups.computeIfAbsent(group, ignored -> new ArrayList<>());
                bounds.computeIfAbsent(group, ignored -> new Bounds());
            } else if (line.startsWith("v ")) {
                String[] p = line.substring(2).trim().split("\\s+");
                vertices.add(new Vertex(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2])));
            } else if (line.startsWith("vt ")) {
                String[] p = line.substring(3).trim().split("\\s+");
                uvs.add(new Uv(Float.parseFloat(p[0]), 1F - Float.parseFloat(p[1])));
            } else if (line.startsWith("usemtl ")) {
                material = line.substring(7).trim();
            } else if (line.startsWith("f ") && group != null) {
                String[] refs = line.substring(2).trim().split("\\s+");
                if (refs.length < 3) continue;
                int texture = BlockTextures.layerOf(material.startsWith("CentreMaterial") ? CENTER : SIDE);
                Vertex a = vertex(vertices, refs[0]);
                Vertex b = vertex(vertices, refs[1]);
                Vertex c = vertex(vertices, refs[2]);
                int face = face(a, b, c);
                float[] data = new float[(refs.length == 3 ? 3 : 6) * 5];
                int at = 0;
                at = put(data, at, vertices, uvs, refs[0]);
                at = put(data, at, vertices, uvs, refs[1]);
                at = put(data, at, vertices, uvs, refs[2]);
                if (refs.length >= 4) {
                    at = put(data, at, vertices, uvs, refs[0]);
                    at = put(data, at, vertices, uvs, refs[2]);
                    put(data, at, vertices, uvs, refs[3]);
                }
                groups.get(group).add(new BakedQuad(data, texture, BakedQuad.NO_CULL, face,
                        face < 0 ? 1F : BlockModels.FACE_BRIGHTNESS[face], BakedQuad.WHITE, BakedQuad.TINT_NONE));
                Bounds box = bounds.get(group);
                for (String ref : refs) box.include(vertex(vertices, ref));
            }
        }
        Map<String, Group> result = new HashMap<>();
        for (Map.Entry<String, List<BakedQuad>> entry : groups.entrySet()) {
            result.put(entry.getKey(), new Group(entry.getValue().toArray(new BakedQuad[0]), bounds.get(entry.getKey()).box()));
        }
        return Map.copyOf(result);
    }

    private static Vertex vertex(List<Vertex> vertices, String ref) {
        return vertices.get(Integer.parseInt(ref.split("/")[0]) - 1);
    }

    private static int put(float[] out, int at, List<Vertex> vertices, List<Uv> uvs, String ref) {
        String[] indices = ref.split("/");
        Vertex v = vertices.get(Integer.parseInt(indices[0]) - 1);
        Uv uv = uvs.get(Integer.parseInt(indices[1]) - 1);
        out[at++] = v.x; out[at++] = v.y; out[at++] = v.z;
        out[at++] = uv.u; out[at++] = uv.v;
        return at;
    }

    private static int face(Vertex a, Vertex b, Vertex c) {
        float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        float acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) return ny >= 0 ? Direction.UP.faceIndex() : Direction.DOWN.faceIndex();
        if (az >= ax) return nz >= 0 ? Direction.SOUTH.faceIndex() : Direction.NORTH.faceIndex();
        return nx >= 0 ? Direction.EAST.faceIndex() : Direction.WEST.faceIndex();
    }

    private static final class Bounds {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        void include(Vertex v) {
            minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
        }
        AABB box() { return minX == Float.POSITIVE_INFINITY ? null : new AABB(minX, minY, minZ, maxX, maxY, maxZ); }
    }

    private MekanismTransmitterModel() {}
}
