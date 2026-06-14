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

    public BoxElement(double x0, double y0, double z0, double x1, double y1, double z1, int[] tex, int[] cull) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.x1 = x1; this.y1 = y1; this.z1 = z1;
        this.tex = tex;
        this.cull = cull;
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

    public BakedQuad[] bake() {
        return BlockModels.box(x0, y0, z0, x1, y1, z1, tex, cull);
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

        return new BoxElement(nx0, y0, nz0, nx1, y1, nz1, nt, nc);
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

    /** Spiegelt vertikal (y -> 1-y); für upside-down Treppen / TOP-Slabs. */
    public BoxElement mirrorY() {
        int[] nt = tex.clone();
        int[] nc = cull.clone();
        /* top<->bottom tauschen */
        nt[0] = tex[1]; nt[1] = tex[0];
        nc[0] = swapTopBottom(cull[1]); nc[1] = swapTopBottom(cull[0]);
        nc[2] = swapTopBottom(cull[2]); nc[3] = swapTopBottom(cull[3]);
        nc[4] = swapTopBottom(cull[4]); nc[5] = swapTopBottom(cull[5]);
        return new BoxElement(x0, 1 - y1, z0, x1, 1 - y0, z1, nt, nc);
    }

    private static int swapTopBottom(int face) {
        return face == 0 ? 1 : face == 1 ? 0 : face;
    }
}
