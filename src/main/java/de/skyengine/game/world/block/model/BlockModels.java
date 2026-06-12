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