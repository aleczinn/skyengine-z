package de.skyengine.game.world.block.model;

/**
 * Ein vorgebackenes Quad eines Blockmodells: 6 Vertices (2 Triangles),
 * 5 Floats pro Vertex (x, y, z, u, v) in lokalen Blockkoordinaten 0..1.
 * <p>
 * cullFace: Face-Index (0=top, 1=bottom, 2=north, 3=south, 4=west, 5=east),
 * gegen dessen Nachbarn geculled wird, oder NO_CULL für immer sichtbare Quads
 * (z.B. Cross-Modelle).
 * <p>
 * tint: gepackte Multiplikationsfarbe 0xRRGGBB (Default {@link #WHITE} = neutral),
 * im Mesher mit der Helligkeit verrechnet. Genutzt für den Wasser-Tint (später Biome).
 */
public record BakedQuad(float[] vertices, int textureLayer, int cullFace, float brightness, int tint) {

    public static final int NO_CULL = -1;

    /** Neutraler Tint (keine Einfärbung). */
    public static final int WHITE = 0xFFFFFF;

    /** Komfort-Konstruktor ohne Tint (neutral weiß) — für alle Nicht-Fluid-Modelle. */
    public BakedQuad(float[] vertices, int textureLayer, int cullFace, float brightness) {
        this(vertices, textureLayer, cullFace, brightness, WHITE);
    }

    /**
     * Sentinel als Texturlayer in {@link BlockModels#box}: dieses Face wird gar nicht
     * erzeugt (z.B. Innenflächen zwischen Pfosten und Arm, die ohnehin verdeckt sind).
     */
    public static final int NO_FACE = -2;
}