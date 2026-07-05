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

    /* 1/64 Block: exakt 4 Einheiten im 8.8-Fixed-Format des Meshers — das Overlay liegt damit
       garantiert VOR der (greedy-gemergten) Basis-Seite, kein Z-Fighting bei Reversed-Z. */
    public static final float OVERLAY_OFFSET = 1F / 64F;

    /**
     * Getintete Seiten-Overlay-Quads (Faces north/south/west/east), um {@link #OVERLAY_OFFSET}
     * nach außen versetzt (Grasblock: Grasrand über der Dirt-Seite). CullFace/Helligkeit wie
     * die jeweilige Basis-Seite — AO im Mesher greift damit identisch.
     */
    public static BakedQuad[] overlaySides(int textureLayer, int tint) {
        BakedQuad[] out = new BakedQuad[4];
        for (int face = 2; face < 6; face++) {
            float[] verts = FACE_VERTICES[face].clone();
            int axis = face <= 3 ? 2 : 0;                       // north/south: z, west/east: x
            float value = (face & 1) == 0 ? -OVERLAY_OFFSET : 1F + OVERLAY_OFFSET; // 2/4 = Minus-Seite
            for (int v = 0; v < 6; v++) {
                verts[v * 5 + axis] = value;
            }
            out[face - 2] = new BakedQuad(verts, textureLayer, face, FACE_BRIGHTNESS[face], tint);
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

        // top (y+):    u=x, v=z
        if (tex[0] != BakedQuad.NO_FACE) {
            float[] t = uvOr(uv, 0, fx0,fz0,  fx0,fz1,  fx1,fz1,  fx1,fz0);
            tmp[n++] = quad(tex[0], cull[0], FACE_BRIGHTNESS[0],
                    fx0,fy1,fz0, t[0],t[1],  fx0,fy1,fz1, t[2],t[3],  fx1,fy1,fz1, t[4],t[5],  fx1,fy1,fz0, t[6],t[7]);
        }
        // bottom (y-): u=x, v=z
        if (tex[1] != BakedQuad.NO_FACE) {
            float[] t = uvOr(uv, 1, fx0,fz0,  fx1,fz0,  fx1,fz1,  fx0,fz1);
            tmp[n++] = quad(tex[1], cull[1], FACE_BRIGHTNESS[1],
                    fx0,fy0,fz0, t[0],t[1],  fx1,fy0,fz0, t[2],t[3],  fx1,fy0,fz1, t[4],t[5],  fx0,fy0,fz1, t[6],t[7]);
        }
        // north (z-):  u=1-x, v=1-y
        if (tex[2] != BakedQuad.NO_FACE) {
            float[] t = uvOr(uv, 2, 1-fx1,1-fy0,  1-fx0,1-fy0,  1-fx0,1-fy1,  1-fx1,1-fy1);
            tmp[n++] = quad(tex[2], cull[2], FACE_BRIGHTNESS[2],
                    fx1,fy0,fz0, t[0],t[1],  fx0,fy0,fz0, t[2],t[3],  fx0,fy1,fz0, t[4],t[5],  fx1,fy1,fz0, t[6],t[7]);
        }
        // south (z+):  u=x, v=1-y
        if (tex[3] != BakedQuad.NO_FACE) {
            float[] t = uvOr(uv, 3, fx0,1-fy0,  fx1,1-fy0,  fx1,1-fy1,  fx0,1-fy1);
            tmp[n++] = quad(tex[3], cull[3], FACE_BRIGHTNESS[3],
                    fx0,fy0,fz1, t[0],t[1],  fx1,fy0,fz1, t[2],t[3],  fx1,fy1,fz1, t[4],t[5],  fx0,fy1,fz1, t[6],t[7]);
        }
        // west (x-):   u=z, v=1-y
        if (tex[4] != BakedQuad.NO_FACE) {
            float[] t = uvOr(uv, 4, fz0,1-fy0,  fz1,1-fy0,  fz1,1-fy1,  fz0,1-fy1);
            tmp[n++] = quad(tex[4], cull[4], FACE_BRIGHTNESS[4],
                    fx0,fy0,fz0, t[0],t[1],  fx0,fy0,fz1, t[2],t[3],  fx0,fy1,fz1, t[4],t[5],  fx0,fy1,fz0, t[6],t[7]);
        }
        // east (x+):   u=1-z, v=1-y
        if (tex[5] != BakedQuad.NO_FACE) {
            float[] t = uvOr(uv, 5, 1-fz1,1-fy0,  1-fz0,1-fy0,  1-fz0,1-fy1,  1-fz1,1-fy1);
            tmp[n++] = quad(tex[5], cull[5], FACE_BRIGHTNESS[5],
                    fx1,fy0,fz1, t[0],t[1],  fx1,fy0,fz0, t[2],t[3],  fx1,fy1,fz0, t[4],t[5],  fx1,fy1,fz1, t[6],t[7]);
        }

        if (n == 6) return tmp;
        BakedQuad[] quads = new BakedQuad[n];
        System.arraycopy(tmp, 0, quads, 0, n);
        return quads;
    }

    /** Liefert {@code uv[face]} falls gesetzt, sonst die Alt-UVs aus der Box-Ausdehnung. */
    private static float[] uvOr(float[][] uv, int face,
                                float a0, float a1, float b0, float b1,
                                float c0, float c1, float d0, float d1) {
        if (uv != null && uv[face] != null) return uv[face];
        return new float[]{a0, a1, b0, b1, c0, c1, d0, d1};
    }

    /** Quad aus 4 Eckpunkten (CCW von außen): A,B,C,D -> Dreiecke (A,B,C),(C,D,A). */
    private static BakedQuad quad(int textureLayer, int cullFace, float brightness,
                                  float ax, float ay, float az, float au, float av,
                                  float bx, float by, float bz, float bu, float bv,
                                  float cx, float cy, float cz, float cu, float cv,
                                  float dx, float dy, float dz, float du, float dv) {
        float[] v = {
                ax,ay,az, au,av,  bx,by,bz, bu,bv,  cx,cy,cz, cu,cv,
                cx,cy,cz, cu,cv,  dx,dy,dz, du,dv,  ax,ay,az, au,av
        };
        return new BakedQuad(v, textureLayer, cullFace, brightness);
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