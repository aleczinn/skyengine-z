package de.skyengine.game.world.block.model;

import de.skyengine.game.physics.AABB;

/**
 * Eine texturierte Box eines Blockmodells (lokale 0..1-Koordinaten), die sich
 * um die Y-Achse drehen und vertikal spiegeln lässt. Aus denselben Boxen werden
 * sowohl Modell-Quads als auch Kollisions-/Umriss-Shapes erzeugt - eine Quelle
 * der Wahrheit für Treppen, Slabs, Zäune & Co.
 *
 * <p>tex/cull sind je Face indiziert (0=top,1=bottom,2=north,3=south,4=west,5=east).
 * cull[f] = Face-Index gegen dessen Nachbarn geculled wird, oder {@link BakedQuad#NO_CULL}.
 */
public final class BoxElement {

    public final double x0, y0, z0, x1, y1, z1;
    public final int[] tex;   // 6
    public final int[] cull;  // 6
    /** Horizontaler Textur-Spiegel (U -> 1-U) für alle Faces, z.B. gespiegelte Tür-Grifftextur. */
    public final boolean mirror;
    /**
     * Optionale explizite Face-UVs (6 Einträge, je {@code null} oder 8 Floats = vier UV-Paare
     * A,B,C,D in 0..1). {@code null}-Einträge fallen auf die aus der Box-Ausdehnung abgeleiteten
     * UVs zurück. Bei {@link #rotateY} rotieren diese UVs mit der Geometrie mit — außer der
     * Aufrufer wirft sie per {@link #withoutFaceUv} weg (uvlock).
     */
    public final float[][] faceUv;

    public BoxElement(double x0, double y0, double z0, double x1, double y1, double z1, int[] tex, int[] cull) {
        this(x0, y0, z0, x1, y1, z1, tex, cull, false, null);
    }

    public BoxElement(double x0, double y0, double z0, double x1, double y1, double z1,
                      int[] tex, int[] cull, boolean mirror) {
        this(x0, y0, z0, x1, y1, z1, tex, cull, mirror, null);
    }

    public BoxElement(double x0, double y0, double z0, double x1, double y1, double z1,
                      int[] tex, int[] cull, boolean mirror, float[][] faceUv) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.x1 = x1; this.y1 = y1; this.z1 = z1;
        this.tex = tex;
        this.cull = cull;
        this.mirror = mirror;
        this.faceUv = faceUv;
    }

    /** Alle Faces dieselbe Textur, kein Culling (typisch für dünne Teile wie Zaunarme). */
    public static BoxElement of(double x0, double y0, double z0, double x1, double y1, double z1, int texture) {
        int[] tex = {texture, texture, texture, texture, texture, texture};
        int[] cull = {BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL};
        return new BoxElement(x0, y0, z0, x1, y1, z1, tex, cull);
    }

    public AABB toAABB() {
        return new AABB(x0, y0, z0, x1, y1, z1);
    }

    /**
     * Verwirft die Face-UVs. {@link #bake} leitet sie dann in {@link BlockModels#box} aus der
     * AKTUELLEN Box-Ausdehnung ab — nach einer Drehung ist das die gedrehte Box, die Textur liegt
     * also weltachsenfest. Genau das ist Minecrafts {@code uvlock} (Treppen, Zäune): die Geometrie
     * dreht sich, die Maserung bleibt stehen.
     *
     * <p>Gegenstück zum Default {@link #transformUv}, der die UVs mitdreht (Stämme).
     */
    public BoxElement withoutFaceUv() {
        if (this.faceUv == null) return this;
        return new BoxElement(x0, y0, z0, x1, y1, z1, tex, cull, mirror, null);
    }

    public BakedQuad[] bake() {
        BakedQuad[] quads = BlockModels.box(x0, y0, z0, x1, y1, z1, tex, cull, faceUv);
        if (this.mirror) {
            for (int i = 0; i < quads.length; i++) quads[i] = flipU(quads[i]);
        }
        return quads;
    }

    /** Spiegelt die U-Koordinate (Index 3 je 5-Float-Vertex) jedes Quads horizontal. */
    private static BakedQuad flipU(BakedQuad quad) {
        float[] v = quad.vertices().clone();
        for (int i = 3; i < v.length; i += 5) v[i] = 1.0F - v[i];
        return new BakedQuad(v, quad.textureLayer(), quad.cullFace(), quad.face(), quad.brightness(),
                BakedQuad.WHITE, BakedQuad.TINT_NONE);
    }

    /** Punkt-Transformation einer Rotation/Spiegelung (lokale 0..1-Koordinaten). */
    private interface PointTransform {
        float[] apply(float x, float y, float z);
    }

    /**
     * Transportiert die Face-UVs durch eine Transformation: Jede Ecke der Ziel-Face bekommt die UV
     * derjenigen Quell-Ecke, die durch die Transformation auf sie abgebildet wird — genau wie in
     * Minecraft, wo die Rotation die Vertex-Positionen dreht und die UVs an ihren Ecken hängen
     * bleiben. Ohne das würde {@link BlockModels#box} die UVs anschließend aus der (wieder
     * achsenparallelen) Box neu ableiten und die Drehung damit verschlucken — das ist der Grund,
     * warum die Maserung liegender Stämme früher quer statt längs lief.
     *
     * <p>Nicht gesetzte {@code faceUv}-Einträge werden vorher aus der QUELL-Box materialisiert;
     * die Reihenfolge ist entscheidend, sonst gäbe es nichts zu transportieren.
     *
     * @param srcOfDest je Ziel-Face der Index der Quell-Face (dieselbe Permutation wie tex/cull)
     */
    private float[][] transformUv(int[] srcOfDest, PointTransform tf,
                                  double nx0, double ny0, double nz0, double nx1, double ny1, double nz1) {
        float[][] out = new float[6][];
        for (int dest = 0; dest < 6; dest++) {
            int src = srcOfDest[dest];
            float[] srcUv = this.faceUv != null && this.faceUv[src] != null
                    ? this.faceUv[src]
                    : BlockModels.extentUv(src, (float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1);
            float[] srcCorners = BlockModels.faceCorners(src,
                    (float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1);
            float[] destCorners = BlockModels.faceCorners(dest,
                    (float) nx0, (float) ny0, (float) nz0, (float) nx1, (float) ny1, (float) nz1);

            float[] uv = new float[8];
            for (int d = 0; d < 4; d++) {
                int match = -1;
                for (int s = 0; s < 4 && match < 0; s++) {
                    float[] p = tf.apply(srcCorners[s * 3], srcCorners[s * 3 + 1], srcCorners[s * 3 + 2]);
                    if (near(p[0], destCorners[d * 3]) && near(p[1], destCorners[d * 3 + 1])
                            && near(p[2], destCorners[d * 3 + 2])) {
                        match = s;
                    }
                }
                if (match < 0) {
                    /* Kann nur bei einer inkonsistenten Slot-Permutation passieren — laut melden
                       statt still falsche UVs zu backen. */
                    throw new IllegalStateException("UV-Transport: Ecke " + d + " der Face " + dest
                            + " hat keinen Partner in Quell-Face " + src);
                }
                uv[d * 2] = srcUv[match * 2];
                uv[d * 2 + 1] = srcUv[match * 2 + 1];
            }
            out[dest] = uv;
        }
        return out;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0E-5F;
    }

    /** 90°-Schritte im Uhrzeigersinn um die Y-Achse (von oben gesehen), N->E->S->W. */
    public BoxElement rotateY(int quarterTurns) {
        BoxElement e = this;
        for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) e = e.rotateCW();
        return e;
    }

    private BoxElement rotateCW() {
        /* (x,z) -> (1-z, x) um die Mitte; Box bleibt achsenparallel */
        double nx0 = 1 - z1, nx1 = 1 - z0;
        double nz0 = x0, nz1 = x1;

        /* Faces rotieren mit: NORTH->EAST->SOUTH->WEST->NORTH (CW) */
        int[] nt = new int[6];
        int[] nc = new int[6];
        nt[0] = tex[0]; nt[1] = tex[1];   // top/bottom bleiben
        nc[0] = cull[0]; nc[1] = cull[1];
        /* alt NORTH(2)->SOUTH(3)? Nein: CW dreht NORTH-Face nach EAST. */
        nt[5] = tex[2]; nt[3] = tex[5]; nt[4] = tex[3]; nt[2] = tex[4];
        nc[5] = rotCullFace(cull[2]); nc[3] = rotCullFace(cull[5]);
        nc[4] = rotCullFace(cull[3]); nc[2] = rotCullFace(cull[4]);

        /* UVs mitdrehen (s. transformUv): (x,y,z) -> (1-z, y, x) */
        float[][] nuv = transformUv(new int[]{0, 1, 4, 5, 3, 2},
                (px, py, pz) -> new float[]{1 - pz, py, px}, nx0, y0, nz0, nx1, y1, nz1);

        return new BoxElement(nx0, y0, nz0, nx1, y1, nz1, nt, nc, mirror, nuv);
    }

    /** Dreht einen Cull-Face-Index um eine CW-Vierteldrehung (N->E->S->W). */
    private static int rotCullFace(int face) {
        return switch (face) {
            case 2 -> 5; // north -> east
            case 5 -> 3; // east -> south
            case 3 -> 4; // south -> west
            case 4 -> 2; // west -> north
            default -> face; // top/bottom/NO_CULL
        };
    }

    /** 90°-Schritte um die X-Achse (für Blockstate {@code x}, v.a. x=180 Upside-down). */
    public BoxElement rotateX(int quarterTurns) {
        BoxElement e = this;
        for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) e = e.rotateXCW();
        return e;
    }

    private BoxElement rotateXCW() {
        /* (y,z) -> (z, 1-y) um die Mitte; Box bleibt achsenparallel */
        double ny0 = z0, ny1 = z1;
        double nz0 = 1 - y1, nz1 = 1 - y0;

        int[] nt = new int[6];
        int[] nc = new int[6];
        nt[4] = tex[4]; nt[5] = tex[5];   // west/east bleiben
        nc[4] = cull[4]; nc[5] = cull[5];
        /* top->north->bottom->south->top : 0->2, 2->1, 1->3, 3->0 */
        nt[2] = tex[0]; nt[1] = tex[2]; nt[3] = tex[1]; nt[0] = tex[3];
        nc[2] = rotCullFaceX(cull[0]); nc[1] = rotCullFaceX(cull[2]);
        nc[3] = rotCullFaceX(cull[1]); nc[0] = rotCullFaceX(cull[3]);

        /* UVs mitdrehen (s. transformUv): (x,y,z) -> (x, z, 1-y) */
        float[][] nuv = transformUv(new int[]{3, 2, 0, 1, 4, 5},
                (px, py, pz) -> new float[]{px, pz, 1 - py}, x0, ny0, nz0, x1, ny1, nz1);

        return new BoxElement(x0, ny0, nz0, x1, ny1, nz1, nt, nc, mirror, nuv);
    }

    private static int rotCullFaceX(int face) {
        return switch (face) {
            case 0 -> 2; // top -> north
            case 2 -> 1; // north -> bottom
            case 1 -> 3; // bottom -> south
            case 3 -> 0; // south -> top
            default -> face; // west/east/NO_CULL/NO_FACE
        };
    }

    /** Spiegelt vertikal (y -> 1-y); für upside-down Treppen / TOP-Slabs. */
    public BoxElement mirrorY() {
        int[] nt = tex.clone();
        int[] nc = cull.clone();
        /* top<->bottom tauschen */
        nt[0] = tex[1]; nt[1] = tex[0];
        nc[0] = swapTopBottom(cull[1]); nc[1] = swapTopBottom(cull[0]);
        nc[2] = swapTopBottom(cull[2]); nc[3] = swapTopBottom(cull[3]);
        nc[4] = swapTopBottom(cull[4]); nc[5] = swapTopBottom(cull[5]);

        /* UVs mitspiegeln (s. transformUv): (x,y,z) -> (x, 1-y, z) */
        float[][] nuv = transformUv(new int[]{1, 0, 2, 3, 4, 5},
                (px, py, pz) -> new float[]{px, 1 - py, pz}, x0, 1 - y1, z0, x1, 1 - y0, z1);

        return new BoxElement(x0, 1 - y1, z0, x1, 1 - y0, z1, nt, nc, mirror, nuv);
    }

    private static int swapTopBottom(int face) {
        return face == 0 ? 1 : face == 1 ? 0 : face;
    }
}
