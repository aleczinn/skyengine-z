package de.skyengine.game.world.block.model;

/**
 * Statische Modell-Factories. Die Quads werden beim Registry-Bake einmalig
 * erzeugt ("gebacken") und vom Mesher nur noch kopiert - kein Branching
 * und keine Allokation mehr im Mesher-Hotpath.
 */
public final class BlockModels {

    /* Face-Indizes: 0=top, 1=bottom, 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x) */
    public static final float[] FACE_BRIGHTNESS = {1.0F, 0.5F, 0.8F, 0.8F, 0.6F, 0.6F};

    /* 6 Vertices (2 Triangles, CCW von außen) pro Face: x,y,z,u,v */
    private static final float[][] FACE_VERTICES = {
            // top (y+)
            {0,1,0, 0,0,  0,1,1, 0,1,  1,1,1, 1,1,  1,1,1, 1,1,  1,1,0, 1,0,  0,1,0, 0,0},
            // bottom (y-)
            {0,0,0, 0,0,  1,0,0, 1,0,  1,0,1, 1,1,  1,0,1, 1,1,  0,0,1, 0,1,  0,0,0, 0,0},
            // north (z-)
            {1,0,0, 0,1,  0,0,0, 1,1,  0,1,0, 1,0,  0,1,0, 1,0,  1,1,0, 0,0,  1,0,0, 0,1},
            // south (z+)
            {0,0,1, 0,1,  1,0,1, 1,1,  1,1,1, 1,0,  1,1,1, 1,0,  0,1,1, 0,0,  0,0,1, 0,1},
            // west (x-)
            {0,0,0, 0,1,  0,0,1, 1,1,  0,1,1, 1,0,  0,1,1, 1,0,  0,1,0, 0,0,  0,0,0, 0,1},
            // east (x+)
            {1,0,1, 0,1,  1,0,0, 1,1,  1,1,0, 1,0,  1,1,0, 1,0,  1,1,1, 0,0,  1,0,1, 0,1}
    };

    public static BakedQuad face(int face, int textureLayer) {
        return new BakedQuad(FACE_VERTICES[face], textureLayer, face, FACE_BRIGHTNESS[face]);
    }

    public static BakedQuad[] cube(int top, int bottom, int north, int south, int west, int east) {
        return new BakedQuad[]{
                face(0, top), face(1, bottom),
                face(2, north), face(3, south),
                face(4, west), face(5, east)
        };
    }

    public static BakedQuad[] cube(int top, int bottom, int side) {
        return cube(top, bottom, side, side, side, side);
    }

    public static BakedQuad[] cubeAll(int all) {
        return cube(all, all, all);
    }

    /**
     * Getintete Seiten-Overlay-Quads (Faces north/south/west/east), EXAKT koplanar zur
     * jeweiligen Basis-Seite (Grasblock: Grasrand über der Dirt-Seite) — identische Vertices
     * wie {@link #face} liefern identische Tiefenwerte; der Mesher emittiert die Basis-Seite
     * solcher Blöcke einzeln (nicht greedy) und der CUTOUT-Pass zeichnet mit „or-equal"-
     * Depth-Func, damit das Overlay den Tiefentest exakt gewinnt (kein Offset, kein Spalt).
     */
    public static BakedQuad[] overlaySides(int textureLayer, int tint, int tintType) {
        BakedQuad[] out = new BakedQuad[4];
        for (int face = 2; face < 6; face++) {
            out[face - 2] = new BakedQuad(FACE_VERTICES[face], textureLayer, face, FACE_BRIGHTNESS[face], tint, tintType);
        }
        return out;
    }

    /**
     * Cross-Modell (Gras, Blumen, Setzlinge): 2 diagonale Ebenen, jeweils
     * doppelseitig gebacken (GL_CULL_FACE bleibt damit global an).
     * Inset 0.146 wie in Minecraft, damit das Quad Breite 1.0 hat.
     */
    public static BakedQuad[] cross(int textureLayer) {
        float min = 0.1464466F, max = 0.8535534F;

        float[] plane1 = diagonalQuad(min, min, max, max);
        float[] plane2 = diagonalQuad(min, max, max, min);

        return new BakedQuad[]{
                new BakedQuad(plane1, textureLayer, BakedQuad.NO_CULL, 1.0F),
                new BakedQuad(reverseWinding(plane1), textureLayer, BakedQuad.NO_CULL, 1.0F),
                new BakedQuad(plane2, textureLayer, BakedQuad.NO_CULL, 1.0F),
                new BakedQuad(reverseWinding(plane2), textureLayer, BakedQuad.NO_CULL, 1.0F)
        };
    }

    /**
     * Erzeugt die Faces einer beliebigen Box (lokale Koordinaten). UVs werden aus
     * der Box-Ausdehnung abgeleitet, damit Texturen nicht gestreckt werden (eine
     * untere Slab-Seite zeigt z.B. die untere Texturhälfte).
     *
     * <p>Faces mit Texturlayer {@link BakedQuad#NO_FACE} werden übersprungen (nicht erzeugt).
     *
     * @param tex  Texturlayer je Face (0=top,1=bottom,2=north,3=south,4=west,5=east) oder NO_FACE
     * @param cull Cull-Face-Index je Face oder {@link BakedQuad#NO_CULL}
     */
    public static BakedQuad[] box(double x0, double y0, double z0, double x1, double y1, double z1,
                                  int[] tex, int[] cull) {
        return box(x0, y0, z0, x1, y1, z1, tex, cull, null);
    }

    /**
     * Wie {@link #box(double, double, double, double, double, double, int[], int[])}, aber mit
     * optionalen expliziten Face-UVs. {@code uv} ist je Face entweder {@code null} (UV aus der
     * Box-Ausdehnung ableiten, Alt-Verhalten) oder ein 8-Float-Array mit den UV-Paaren der vier
     * Eckpunkte A,B,C,D (0..1, v von oben). Damit lassen sich dünne Verbindungsteile (z.B.
     * Glass-Pane-Kanten) aus einem festen Texturstreifen mappen, der dann bei {@code rotateY}
     * korrekt mitrotiert, statt je nach Arm-Richtung eine andere Texturregion zu treffen.
     */
    public static BakedQuad[] box(double x0, double y0, double z0, double x1, double y1, double z1,
                                  int[] tex, int[] cull, float[][] uv) {
        float fx0 = (float) x0, fy0 = (float) y0, fz0 = (float) z0;
        float fx1 = (float) x1, fy1 = (float) y1, fz1 = (float) z1;

        BakedQuad[] tmp = new BakedQuad[6];
        int n = 0;

        for (int face = 0; face < 6; face++) {
            if (tex[face] == BakedQuad.NO_FACE) continue;
            float[] c = faceCorners(face, fx0, fy0, fz0, fx1, fy1, fz1);
            float[] t = uv != null && uv[face] != null
                    ? uv[face]
                    : extentUv(face, fx0, fy0, fz0, fx1, fy1, fz1);
            tmp[n++] = quad(tex[face], cull[face], face, FACE_BRIGHTNESS[face], c, t);
        }

        if (n == 6) return tmp;
        BakedQuad[] quads = new BakedQuad[n];
        System.arraycopy(tmp, 0, quads, 0, n);
        return quads;
    }

    /**
     * Die vier Eckpunkte einer Box-Face in der Reihenfolge A,B,C,D (CCW von außen), je 3 Floats.
     * Bei allen vier Seitenflächen gilt aus Außensicht: A = unten-links, B = unten-rechts,
     * C = oben-rechts, D = oben-links.
     */
    public static float[] faceCorners(int face, float x0, float y0, float z0, float x1, float y1, float z1) {
        return switch (face) {
            case 0 -> new float[]{x0,y1,z0,  x0,y1,z1,  x1,y1,z1,  x1,y1,z0}; // top (y+)
            case 1 -> new float[]{x0,y0,z0,  x1,y0,z0,  x1,y0,z1,  x0,y0,z1}; // bottom (y-)
            case 2 -> new float[]{x1,y0,z0,  x0,y0,z0,  x0,y1,z0,  x1,y1,z0}; // north (z-)
            case 3 -> new float[]{x0,y0,z1,  x1,y0,z1,  x1,y1,z1,  x0,y1,z1}; // south (z+)
            case 4 -> new float[]{x0,y0,z0,  x0,y0,z1,  x0,y1,z1,  x0,y1,z0}; // west (x-)
            default -> new float[]{x1,y0,z1,  x1,y0,z0,  x1,y1,z0,  x1,y1,z1}; // east (x+)
        };
    }

    /**
     * Die aus der Box-Ausdehnung abgeleiteten UVs einer Face (4 Paare A,B,C,D), damit Texturen
     * nicht gestreckt werden — eine untere Slab-Seite zeigt so die untere Texturhälfte.
     * Achsenzuordnung: top/bottom u=x,v=z; north u=1-x; south u=x; west u=z; east u=1-z;
     * alle Seiten v=1-y (v läuft von oben).
     */
    public static float[] extentUv(int face, float x0, float y0, float z0, float x1, float y1, float z1) {
        return switch (face) {
            case 0 -> new float[]{x0,z0,  x0,z1,  x1,z1,  x1,z0};
            case 1 -> new float[]{x0,z0,  x1,z0,  x1,z1,  x0,z1};
            case 2 -> new float[]{1-x1,1-y0,  1-x0,1-y0,  1-x0,1-y1,  1-x1,1-y1};
            case 3 -> new float[]{x0,1-y0,  x1,1-y0,  x1,1-y1,  x0,1-y1};
            case 4 -> new float[]{z0,1-y0,  z1,1-y0,  z1,1-y1,  z0,1-y1};
            default -> new float[]{1-z1,1-y0,  1-z0,1-y0,  1-z0,1-y1,  1-z1,1-y1};
        };
    }

    /**
     * Quad aus 4 Eckpunkten (CCW von außen): A,B,C,D -> Dreiecke (A,B,C),(C,D,A).
     *
     * @param face geometrische Normalenrichtung (auch wenn cullFace == NO_CULL), s. {@link BakedQuad}
     */
    private static BakedQuad quad(int textureLayer, int cullFace, int face, float brightness,
                                  float[] c, float[] t) {
        float[] v = {
                c[0],c[1],c[2],   t[0],t[1],
                c[3],c[4],c[5],   t[2],t[3],
                c[6],c[7],c[8],   t[4],t[5],
                c[6],c[7],c[8],   t[4],t[5],
                c[9],c[10],c[11], t[6],t[7],
                c[0],c[1],c[2],   t[0],t[1]
        };
        return new BakedQuad(v, textureLayer, cullFace, face, brightness, BakedQuad.WHITE, BakedQuad.TINT_NONE);
    }

    /** Hängt mehrere Quad-Arrays aneinander. */
    public static BakedQuad[] concat(BakedQuad[]... parts) {
        int total = 0;
        for (BakedQuad[] p : parts) total += p.length;
        BakedQuad[] out = new BakedQuad[total];
        int i = 0;
        for (BakedQuad[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    /** Backt eine Liste von BoxElementen zu einem Modell. */
    public static BakedQuad[] bake(java.util.List<BoxElement> elements) {
        BakedQuad[][] parts = new BakedQuad[elements.size()][];
        for (int i = 0; i < elements.size(); i++) parts[i] = elements.get(i).bake();
        return concat(parts);
    }

    private static float[] diagonalQuad(float x0, float z0, float x1, float z1) {
        return new float[]{
                x0,0,z0, 0,1,  x1,0,z1, 1,1,  x1,1,z1, 1,0,
                x1,1,z1, 1,0,  x0,1,z0, 0,0,  x0,0,z0, 0,1
        };
    }

    private static float[] reverseWinding(float[] vertices) {
        float[] out = new float[vertices.length];
        for (int v = 0; v < 6; v++) {
            System.arraycopy(vertices, (5 - v) * 5, out, v * 5, 5);
        }
        return out;
    }

    private BlockModels() {}
}