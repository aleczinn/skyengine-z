package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.model.BlockModels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zentrale Materialtabelle fuer 8-Byte-Quads. Textur, Tint, Face-Shade und RenderLayer liegen
 * einmal pro Material statt viermal pro Vertex. Der Quad-Datensatz traegt nur die 16-Bit-ID.
 */
public final class LodMaterialTable implements VoxelLodMesher.MaterialResolver {

    public static final int FLAG_DENSE_ALPHA = 1;

    public record Entry(int textureLayer, int tintRgb, int tintType, int faceShade,
                        RenderLayer renderLayer, int flags, boolean occludes) {}

    private final int[][][] materialIds;
    private final List<Entry> entries;

    public LodMaterialTable(LodBlockAppearance appearance) {
        int states = BlockRegistry.getStateCount();
        this.materialIds = new int[states][3][2];
        ArrayList<Entry> built = new ArrayList<>();
        Map<Entry, Integer> interned = new HashMap<>();
        for (int state = 0; state < states; state++) for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                boolean top = axis == 1 && side == 1;
                int texture = top ? appearance.topLayer(state) : appearance.sideLayer(state);
                if (texture < 0) {
                    this.materialIds[state][axis][side] = -1;
                    continue;
                }
                int tint = top ? appearance.topTint(state) : appearance.sideTint(state);
                int tintType = top ? appearance.topTintType(state) : appearance.sideTintType(state);
                int face = face(axis, side != 0);
                RenderLayer renderLayer = appearance.isTranslucent(state)
                        ? RenderLayer.TRANSLUCENT : appearance.isDense(state)
                        ? RenderLayer.OPAQUE : BlockRegistry.getState(state).getRenderLayer();
                int flags = appearance.isDense(state) ? FLAG_DENSE_ALPHA : 0;
                Entry entry = new Entry(texture, tint, tintType,
                        Math.round(BlockModels.FACE_BRIGHTNESS[face] * 255F), renderLayer,
                        flags, !appearance.isTranslucent(state));
                int id = interned.computeIfAbsent(entry, ignored -> {
                    if (built.size() >= 65536) throw new IllegalStateException("Mehr als 65536 LOD-Materialien");
                    built.add(entry);
                    return built.size() - 1;
                });
                this.materialIds[state][axis][side] = id;
            }
        }
        this.entries = List.copyOf(built);
    }

    @Override
    public VoxelLodMesher.Material resolve(int stateId, int axis, boolean positiveSide) {
        if (stateId < 0 || stateId >= this.materialIds.length || axis < 0 || axis > 2) return null;
        int id = this.materialIds[stateId][axis][positiveSide ? 1 : 0];
        if (id < 0) return null;
        Entry entry = this.entries.get(id);
        return new VoxelLodMesher.Material(id, entry.renderLayer, 0, entry.flags, entry.occludes);
    }

    public List<Entry> entries() { return this.entries; }

    /** Direkt hochladbares std430-Layout, vier uint pro Material. */
    public int[] gpuWords() {
        int[] words = new int[this.entries.size() * 4];
        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            int base = i * 4;
            words[base] = entry.textureLayer;
            words[base + 1] = entry.tintRgb;
            words[base + 2] = entry.flags | entry.faceShade << 8
                    | (entry.tintType & 0xFF) << 16 | entry.renderLayer.ordinal() << 24;
            words[base + 3] = 0;
        }
        return words;
    }

    private static int face(int axis, boolean positive) {
        return switch (axis) {
            case 0 -> positive ? 5 : 4;
            case 1 -> positive ? 0 : 1;
            case 2 -> positive ? 3 : 2;
            default -> throw new IllegalArgumentException("Achse: " + axis);
        };
    }
}
