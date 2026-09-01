package de.skyengine.game.world.block.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only registry for compact base-plus-overlay materials.
 *
 * <p>The lower 15 bits of a compact material handle are either a direct texture-array layer or
 * an index into this table. Bit 15 selects the table. Existing direct materials therefore retain
 * their zero-lookup shader path. Entries are never renumbered: meshes that are being replaced by
 * an asynchronous resource reload keep referring to valid descriptors.</p>
 */
public final class CompactCompositeMaterialTable {

    public static final int COMPOSITE_BIT = 0x8000;
    public static final int INDEX_MASK = 0x7FFF;
    public static final int ENTRY_INTS = 4;

    /** Alpha-tested overlay replaces the base only where the overlay passes the cutoff. */
    public static final int MODE_CUTOUT_REPLACE = 1;

    /** Diagnostic-only classification; it is not interpreted by the terrain shader. */
    public static final int KIND_NONE = 0;
    public static final int KIND_GRASS = 1;

    public record Entry(int baseLayer, int overlayLayer, int overlayTintType,
                        int overlayFixedTint, int mode) {
        public Entry {
            requireDirectLayer("baseLayer", baseLayer);
            requireDirectLayer("overlayLayer", overlayLayer);
            if (overlayTintType < BakedQuad.TINT_NONE || overlayTintType > BakedQuad.TINT_FOLIAGE) {
                throw new IllegalArgumentException("unsupported overlay tint type: " + overlayTintType);
            }
            if ((overlayFixedTint & ~0xFFFFFF) != 0) {
                throw new IllegalArgumentException("overlay tint outside 0xRRGGBB: " + overlayFixedTint);
            }
            if (mode != MODE_CUTOUT_REPLACE) {
                throw new IllegalArgumentException("unsupported composite mode: " + mode);
            }
        }

        int packedFlags() {
            return this.mode | this.overlayTintType << 8;
        }
    }

    private static final Map<Entry, Integer> IDS = new LinkedHashMap<>();
    private static final List<Entry> ENTRIES = new ArrayList<>();

    public static synchronized int intern(Entry entry) {
        Integer existing = IDS.get(entry);
        if (existing != null) return COMPOSITE_BIT | existing;
        int index = ENTRIES.size();
        if (index > INDEX_MASK) {
            throw new IllegalStateException("too many compact composite materials: " + (index + 1));
        }
        IDS.put(entry, index);
        ENTRIES.add(entry);
        return COMPOSITE_BIT | index;
    }

    public static int directHandle(int textureLayer) {
        requireDirectLayer("textureLayer", textureLayer);
        return textureLayer;
    }

    public static boolean isComposite(int materialHandle) {
        return (materialHandle & COMPOSITE_BIT) != 0;
    }

    public static int compositeIndex(int materialHandle) {
        if (!isComposite(materialHandle)) {
            throw new IllegalArgumentException("not a composite material handle: " + materialHandle);
        }
        return materialHandle & INDEX_MASK;
    }

    /** std430 {@code uvec4[]} payload. A sentinel entry keeps binding 4 valid without composites. */
    public static synchronized int[] gpuSnapshot() {
        int count = Math.max(1, ENTRIES.size());
        int[] data = new int[count * ENTRY_INTS];
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry entry = ENTRIES.get(i);
            int base = i * ENTRY_INTS;
            data[base] = entry.baseLayer;
            data[base + 1] = entry.overlayLayer;
            data[base + 2] = entry.packedFlags();
            data[base + 3] = entry.overlayFixedTint;
        }
        return data;
    }

    public static synchronized Entry entry(int materialHandle) {
        return ENTRIES.get(compositeIndex(materialHandle));
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    private static void requireDirectLayer(String name, int layer) {
        if (layer < 0 || layer > INDEX_MASK) {
            throw new IllegalArgumentException(name + " outside direct 15-bit range: " + layer);
        }
    }

    private CompactCompositeMaterialTable() {}
}
