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
     * @param tex  Texturlayer je Face (0=top,1=bottom,2=north,3=south,4=west,5=east)
     * @param cull Cull-Face-Index je Face oder {@link BakedQuad#NO_CULL}
     */
    public static BakedQuad[] box(double x0, double y0, double z0, double x1, double y1, double z1,
                                  int[] tex, int[] cull) {
        float fx0 = (float) x0, fy0 = (float) y0, fz0 = (float) z0;
        float fx1 = (float) x1, fy1 = (float) y1, fz1 = (float) z1;

        BakedQuad[] quads = new BakedQuad[6];
        int n = 0;

        // top (y+):    u=x, v=z
        quads[n++] = quad(tex[0], cull[0], FACE_BRIGHTNESS[0],
                fx0,fy1,fz0, fx0,fz0,  fx0,fy1,fz1, fx0,fz1,  fx1,fy1,fz1, fx1,fz1,  fx1,fy1,fz0, fx1,fz0);
        // bottom (y-): u=x, v=z
        quads[n++] = quad(tex[1], cull[1], FACE_BRIGHTNESS[1],
                fx0,fy0,fz0, fx0,fz0,  fx1,fy0,fz0, fx1,fz0,  fx1,fy0,fz1, fx1,fz1,  fx0,fy0,fz1, fx0,fz1);
        // north (z-):  u=1-x, v=1-y
        quads[n++] = quad(tex[2], cull[2], FACE_BRIGHTNESS[2],
                fx1,fy0,fz0, 1-fx1,1-fy0,  fx0,fy0,fz0, 1-fx0,1-fy0,  fx0,fy1,fz0, 1-fx0,1-fy1,  fx1,fy1,fz0, 1-fx1,1-fy1);
        // south (z+):  u=x, v=1-y
        quads[n++] = quad(tex[3], cull[3], FACE_BRIGHTNESS[3],
                fx0,fy0,fz1, fx0,1-fy0,  fx1,fy0,fz1, fx1,1-fy0,  fx1,fy1,fz1, fx1,1-fy1,  fx0,fy1,fz1, fx0,1-fy1);
        // west (x-):   u=z, v=1-y
        quads[n++] = quad(tex[4], cull[4], FACE_BRIGHTNESS[4],
                fx0,fy0,fz0, fz0,1-fy0,  fx0,fy0,fz1, fz1,1-fy0,  fx0,fy1,fz1, fz1,1-fy1,  fx0,fy1,fz0, fz0,1-fy1);
        // east (x+):   u=1-z, v=1-y
        quads[n++] = quad(tex[5], cull[5], FACE_BRIGHTNESS[5],
                fx1,fy0,fz1, 1-fz1,1-fy0,  fx1,fy0,fz0, 1-fz0,1-fy0,  fx1,fy1,fz0, 1-fz0,1-fy1,  fx1,fy1,fz1, 1-fz1,1-fy1);

        return quads;
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