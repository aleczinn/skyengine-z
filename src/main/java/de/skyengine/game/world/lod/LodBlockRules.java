package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Datengetriebene Vereinfachungsregeln für LOD-Spalten. */
public final class LodBlockRules {

    private record Rule(String replacement, boolean ignore) {}

    private static final Map<Identifier, Rule> RULES = new HashMap<>();
    private static volatile int[] resolved;

    public static void register(Identifier id, String replacement, boolean ignore) {
        if (replacement != null && ignore) {
            throw new IllegalArgumentException(id + " setzt lod_replacement und lod_ignore zugleich");
        }
        RULES.put(id, new Rule(replacement, ignore));
        resolved = null;
    }

    /** Liefert AIR zum Auslassen, sonst den vereinfachten Default-State. */
    public static int simplify(int stateId) {
        int[] table = resolved;
        if (table == null) {
            synchronized (LodBlockRules.class) {
                if (resolved == null) resolved = resolve();
                table = resolved;
            }
        }
        return table[stateId];
    }

    /** Volumenpfad darf zusaetzlich die kompakt darstellbaren Cross-Modelle behalten. */
    public static int simplifyVolume(int stateId) {
        int simplified = simplify(stateId);
        return simplified == 0 && isCross(BlockRegistry.getState(stateId)) ? stateId : simplified;
    }

    /** Screen-Space-Fehler, getrennt von Materialmasse und Belegungsgrad. */
    public static int volumeImportance(int stateId) {
        BlockState state = BlockRegistry.getState(stateId);
        if (isCross(state)) return 32;
        if (state.isLeaves()) return 24;
        String id = state.getBlock().getIdentifier().toString();
        if (id.endsWith("_log") || id.endsWith("_wood") || id.contains("structure")) return 24;
        return 4;
    }

    public static int volumeFingerprint() {
        int hash = fingerprint();
        for (int state = 0; state < BlockRegistry.getStateCount(); state++) {
            hash = 31 * hash + simplifyVolume(state);
        }
        return hash;
    }

    /** Fingerabdruck der Runtime-Registry inklusive aller aufgelösten LOD-Regeln. */
    public static int fingerprint() {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        int[] table = resolved;
        if (table == null) simplify(0);
        table = resolved;
        for (int stateId = 0; stateId < table.length; stateId++) {
            String id = BlockRegistry.getState(stateId).getBlock().getIdentifier().toString();
            for (int i = 0; i < id.length(); i++) crc.update(id.charAt(i));
            crc.update(stateId);
            crc.update(stateId >>> 8);
            crc.update(table[stateId]);
            crc.update(table[stateId] >>> 8);
        }
        return (int) crc.getValue();
    }

    private static int[] resolve() {
        int[] table = new int[BlockRegistry.getStateCount()];
        for (int stateId = 0; stateId < table.length; stateId++) {
            BlockState state = BlockRegistry.getState(stateId);
            Block block = state.getBlock();
            Rule rule = RULES.get(block.getIdentifier());
            if (rule != null && rule.ignore) {
                table[stateId] = 0;
                continue;
            }
            if (rule != null && rule.replacement != null) {
                Block replacement = BlockRegistry.get(Identifier.of(rule.replacement));
                if (replacement == null) {
                    throw new IllegalStateException("Unbekannter LOD-Ersatz " + rule.replacement
                            + " für " + block.getIdentifier());
                }
                table[stateId] = replacement.getDefaultState().getId();
                continue;
            }
            /* Volle Modell-/Kollisionswürfel sowie Fluide sind ohne Sonderregel darstellbar. */
            if (state.isFluid() || state.getCollisionShape().isFullCube()) {
                table[stateId] = stateId;
            } else {
                table[stateId] = 0;
            }
        }
        return table;
    }

    private static boolean isCross(BlockState state) {
        BakedQuad[] model = state.getModel();
        if (model.length != 4) return false;
        int layer = model[0].textureLayer();
        for (BakedQuad quad : model) {
            if (quad.cullFace() != BakedQuad.NO_CULL || quad.face() != BakedQuad.NO_DIRECTION
                    || quad.textureLayer() != layer) return false;
        }
        return true;
    }

    private LodBlockRules() {}
}
