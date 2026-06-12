package de.skyengine.game.world.block.model;

/**
 * Ein vorgebackenes Quad eines Blockmodells: 6 Vertices (2 Triangles),
 * 5 Floats pro Vertex (x, y, z, u, v) in lokalen Blockkoordinaten 0..1.
 * <p>
 * cullFace: Face-Index (0=top, 1=bottom, 2=north, 3=south, 4=west, 5=east),
 * gegen dessen Nachbarn geculled wird, oder NO_CULL für immer sichtbare Quads
 * (z.B. Cross-Modelle).
 */
public record BakedQuad(float[] vertices, int textureLayer, int cullFace, float brightness) {

    public static final int NO_CULL = -1;
}