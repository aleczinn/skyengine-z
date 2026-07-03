package de.skyengine.game.world.block;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sammelt alle von Blockmodellen referenzierten Texturpfade und vergibt
 * Layer-Indizes für das TextureArray. Die Indizes werden beim Model-Bake
 * vergeben (CPU-seitig), das GL-Array wird später im Renderer daraus gebaut.
 */
public final class BlockTextures {

    private static final Map<String, Integer> LAYERS = new LinkedHashMap<>();

    /** Pfad relativ zum resources-Ordner, z.B. "game/texture/block/stone.png" */
    public static int layerOf(String path) {
        return LAYERS.computeIfAbsent(path, p -> LAYERS.size());
    }

    public static String[] getOrderedPaths() {
        return LAYERS.keySet().toArray(new String[0]);
    }

    private BlockTextures() {}
}