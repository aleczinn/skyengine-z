package de.skyengine.graphics;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;

/**
 * Baut aus den gebackenen Quads eines Block-States die interleaved Vertex-Daten
 * {@code [x,y,z, u,v,layer, r,g,b]} (9 Floats/Vertex) — das gemeinsame Format von
 * EntityRenderer, HeldItemMeshes und ItemIconRenderer. Extrahiert aus
 * {@code EntityRenderer.build}; die VAO/VBO-Hülle bleibt bewusst je Renderer
 * (unterschiedliche Lebenszyklen), nur der Daten-Bau ist geteilt.
 */
public final class BlockStateMesh {

    public static final int FLOATS_PER_VERTEX = 9;

    /**
     * Interleaved Vertex-Daten des States (inkl. Seiten-Overlay, fester Fallback-Tint,
     * Helligkeit einmultipliziert) oder {@code null} bei leerem Modell.
     */
    public static float[] interleave(int stateId) {
        BakedQuad[] quads = Blocks.getState(stateId).getModel();
        if (quads == null || quads.length == 0) return null;
        /* Seiten-Overlay (Grasblock) anhängen wie beim Inventar-Icon — sonst bleiben die
           Gras-Seitenstreifen grau. */
        BakedQuad[] overlay = Blocks.getState(stateId).getOverlay();
        if (overlay.length > 0) quads = BlockModels.concat(quads, overlay);

        int verts = 0;
        for (BakedQuad q : quads) verts += q.vertices().length / 5;
        if (verts == 0) return null;

        float[] data = new float[verts * FLOATS_PER_VERTEX];
        int p = 0;
        for (BakedQuad q : quads) {
            float[] v = q.vertices();
            int n = v.length / 5;
            /* Fester Fallback-Tint aus dem Quad (kein Biome-Grid). */
            int tint = q.tint();
            float r = q.brightness() * ((tint >> 16) & 0xFF) / 255F;
            float g = q.brightness() * ((tint >> 8) & 0xFF) / 255F;
            float b = q.brightness() * (tint & 0xFF) / 255F;
            for (int i = 0; i < n; i++) {
                data[p++] = v[i * 5];
                data[p++] = v[i * 5 + 1];
                data[p++] = v[i * 5 + 2];
                data[p++] = v[i * 5 + 3];
                data[p++] = v[i * 5 + 4];
                data[p++] = q.textureLayer();
                data[p++] = r;
                data[p++] = g;
                data[p++] = b;
            }
        }
        return data;
    }

    private BlockStateMesh() {}
}
