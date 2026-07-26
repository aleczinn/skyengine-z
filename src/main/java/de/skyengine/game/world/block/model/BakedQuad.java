package de.skyengine.game.world.block.model;

/**
 * Ein vorgebackenes Quad eines Blockmodells: 6 Vertices (2 Triangles),
 * 5 Floats pro Vertex (x, y, z, u, v) in lokalen Blockkoordinaten 0..1.
 * <p>
 * cullFace: Face-Index (0=top, 1=bottom, 2=north, 3=south, 4=west, 5=east),
 * gegen dessen Nachbarn geculled wird, oder NO_CULL für immer sichtbare Quads
 * (z.B. Cross-Modelle).
 * <p>
 * face: die GEOMETRISCHE Normalenrichtung (gleiche Face-Indizes) — unabhängig davon, ob das
 * Quad gecullt wird. Die Oberseite eines Halbblocks liegt z.B. bei y=0.5 im Blockinneren und
 * hat deshalb kein cullFace, zeigt aber trotzdem nach oben. Der Mesher braucht diese Richtung
 * fürs Ambient Occlusion. {@link #NO_DIRECTION} für nicht achsenparallele Quads (Cross) und
 * für nicht-planare Fluid-Geometrie.
 * <p>
 * tint: gepackte Multiplikationsfarbe 0xRRGGBB (Default {@link #WHITE} = neutral),
 * im Mesher mit der Helligkeit verrechnet. Genutzt für Wasser- und Vegetations-Tint.
 * <p>
 * tintType: {@link #TINT_NONE} = fester Tint-Wert; {@link #TINT_GRASS}/{@link #TINT_FOLIAGE}
 * = der Mesher ersetzt den Tint zur Mesh-Zeit durch die Biome-Farbe an der Blockposition
 * (tint bleibt als Fallback, z.B. für Item-Icons und Chunks ohne Tint-Grid).
 */
public record BakedQuad(float[] vertices, int textureLayer, int cullFace, int face,
                        float brightness, int tint, int tintType) {

    public static final int NO_CULL = -1;

    /**
     * Keine achsenparallele Normalenrichtung (s. face) — numerisch gleich {@link #NO_CULL},
     * damit die Komfort-Konstruktoren ohne face das bisherige Verhalten beibehalten.
     */
    public static final int NO_DIRECTION = -1;

    /** Neutraler Tint (keine Einfärbung). */
    public static final int WHITE = 0xFFFFFF;

    /* Biome-Tint-Typen (s. tintType) */
    public static final int TINT_NONE = 0;
    public static final int TINT_GRASS = 1;
    public static final int TINT_FOLIAGE = 2;

    /** Komfort-Konstruktor ohne Tint (neutral weiß) — für alle Nicht-Fluid-Modelle. */
    public BakedQuad(float[] vertices, int textureLayer, int cullFace, float brightness) {
        this(vertices, textureLayer, cullFace, brightness, WHITE, TINT_NONE);
    }

    /** Komfort-Konstruktor mit festem Tint ohne Biome-Abhängigkeit (z.B. Wasser). */
    public BakedQuad(float[] vertices, int textureLayer, int cullFace, float brightness, int tint) {
        this(vertices, textureLayer, cullFace, brightness, tint, TINT_NONE);
    }

    /**
     * Komfort-Konstruktor ohne explizite Face-Richtung: die Richtung wird aus dem cullFace
     * übernommen. Passt für alle Quads, die bündig auf der Blockgrenze liegen und dort auch
     * gecullt werden (volle Würfel-Faces, Seiten-Overlays) sowie für Quads ohne beides
     * (Cross, Fluids -> NO_DIRECTION).
     */
    public BakedQuad(float[] vertices, int textureLayer, int cullFace, float brightness, int tint, int tintType) {
        this(vertices, textureLayer, cullFace, cullFace, brightness, tint, tintType);
    }

    /**
     * Sentinel als Texturlayer in {@link BlockModels#box}: dieses Face wird gar nicht
     * erzeugt (z.B. Innenflächen zwischen Pfosten und Arm, die ohnehin verdeckt sind).
     */
    public static final int NO_FACE = -2;
}
