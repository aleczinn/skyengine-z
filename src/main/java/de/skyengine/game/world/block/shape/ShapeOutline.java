package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Berechnet die zusammengefasste Umriss-Silhouette einer Menge achsenparalleler Boxen — wie
 * Minecrafts {@code VoxelShape}-Kanten: nur die Außen- und Knickkanten der Vereinigung, keine
 * Innen- oder Doppelkanten. So wird z.B. ein Zaun (Pfosten + Arme) als <b>ein</b> Umriss gezeichnet
 * statt als mehrere überlagerte Drahtboxen.
 *
 * <p>Verfahren: Die Box-Grenzen spannen ein nicht-uniformes Gitter auf; jede Zelle ist „gefüllt",
 * wenn ihr Mittelpunkt in einer Box liegt. Eine Gitterkante gehört zur Silhouette, wenn das Muster
 * der vier sie umgebenden Zellen einen Außen- (1 gefüllt), Innenknick- (3 gefüllt) oder Berührfall
 * (2 diagonal) bildet — flache Flächen (2 benachbart) und Voll/Leer (0/4) liefern keine Kante.
 *
 * <p>Ausgabe: Liniensegmente (je 2 Punkte × 3 Floats) in lokalen Blockkoordinaten, leicht von der
 * Form-Mitte nach außen aufgeblasen gegen Z-Fighting.
 */
public final class ShapeOutline {

    private ShapeOutline() {}

    public static float[] build(AABB[] boxes, float inflate) {
        if (boxes.length == 0) return new float[0];

        double[] xs = axisCoords(boxes, 0);
        double[] ys = axisCoords(boxes, 1);
        double[] zs = axisCoords(boxes, 2);
        int nx = xs.length - 1, ny = ys.length - 1, nz = zs.length - 1;

        boolean[][][] filled = new boolean[nx][ny][nz];
        for (int i = 0; i < nx; i++) {
            double cx = (xs[i] + xs[i + 1]) * 0.5;
            for (int j = 0; j < ny; j++) {
                double cy = (ys[j] + ys[j + 1]) * 0.5;
                for (int k = 0; k < nz; k++) {
                    double cz = (zs[k] + zs[k + 1]) * 0.5;
                    filled[i][j][k] = inside(boxes, cx, cy, cz);
                }
            }
        }

        /* Aufblas-Richtung aus der Bounding-Box-Mitte der gesamten Form. */
        double mx = (xs[0] + xs[nx]) * 0.5, my = (ys[0] + ys[ny]) * 0.5, mz = (zs[0] + zs[nz]) * 0.5;

        List<float[]> segs = new ArrayList<>();

        /* Kanten entlang X: an Gitterlinie (y=ys[j], z=zs[k]), Spanne über Spalte i. */
        for (int j = 0; j <= ny; j++)
            for (int k = 0; k <= nz; k++)
                for (int i = 0; i < nx; i++)
                    if (drawEdge(cell(filled, i, j - 1, k - 1, nx, ny, nz), cell(filled, i, j - 1, k, nx, ny, nz),
                                 cell(filled, i, j, k - 1, nx, ny, nz), cell(filled, i, j, k, nx, ny, nz)))
                        addSeg(segs, xs[i], ys[j], zs[k], xs[i + 1], ys[j], zs[k], mx, my, mz, inflate);

        /* Kanten entlang Y. */
        for (int i = 0; i <= nx; i++)
            for (int k = 0; k <= nz; k++)
                for (int j = 0; j < ny; j++)
                    if (drawEdge(cell(filled, i - 1, j, k - 1, nx, ny, nz), cell(filled, i - 1, j, k, nx, ny, nz),
                                 cell(filled, i, j, k - 1, nx, ny, nz), cell(filled, i, j, k, nx, ny, nz)))
                        addSeg(segs, xs[i], ys[j], zs[k], xs[i], ys[j + 1], zs[k], mx, my, mz, inflate);

        /* Kanten entlang Z. */
        for (int i = 0; i <= nx; i++)
            for (int j = 0; j <= ny; j++)
                for (int k = 0; k < nz; k++)
                    if (drawEdge(cell(filled, i - 1, j - 1, k, nx, ny, nz), cell(filled, i - 1, j, k, nx, ny, nz),
                                 cell(filled, i, j - 1, k, nx, ny, nz), cell(filled, i, j, k, nx, ny, nz)))
                        addSeg(segs, xs[i], ys[j], zs[k], xs[i], ys[j], zs[k + 1], mx, my, mz, inflate);

        float[] out = new float[segs.size() * 6];
        for (int s = 0; s < segs.size(); s++) System.arraycopy(segs.get(s), 0, out, s * 6, 6);
        return out;
    }

    /** Vier Zellen um eine Kante (2×2 in der Senkrechtebene). Außenkante (1), Innenknick (3) oder
        Berührfall (2 diagonal) liefern eine sichtbare Kante; flache Fläche (2 benachbart) und 0/4 nicht. */
    private static boolean drawEdge(boolean a, boolean b, boolean c, boolean d) {
        int n = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
        if (n == 1 || n == 3) return true;
        if (n == 2) return (a && d) || (b && c); // nur diagonale Berührung
        return false;
    }

    private static boolean cell(boolean[][][] f, int i, int j, int k, int nx, int ny, int nz) {
        if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) return false;
        return f[i][j][k];
    }

    private static boolean inside(AABB[] boxes, double x, double y, double z) {
        for (AABB b : boxes) {
            if (x > b.minX && x < b.maxX && y > b.minY && y < b.maxY && z > b.minZ && z < b.maxZ) return true;
        }
        return false;
    }

    private static double[] axisCoords(AABB[] boxes, int axis) {
        TreeSet<Double> set = new TreeSet<>();
        for (AABB b : boxes) {
            set.add(axis == 0 ? b.minX : axis == 1 ? b.minY : b.minZ);
            set.add(axis == 0 ? b.maxX : axis == 1 ? b.maxY : b.maxZ);
        }
        double[] out = new double[set.size()];
        int i = 0;
        for (double d : set) out[i++] = d;
        return out;
    }

    private static void addSeg(List<float[]> segs, double x0, double y0, double z0,
                               double x1, double y1, double z1,
                               double mx, double my, double mz, float inflate) {
        segs.add(new float[]{
                infl(x0, mx, inflate), infl(y0, my, inflate), infl(z0, mz, inflate),
                infl(x1, mx, inflate), infl(y1, my, inflate), infl(z1, mz, inflate)
        });
    }

    /** Schiebt eine Koordinate um {@code inflate} von der Form-Mitte weg (gegen Z-Fighting). */
    private static float infl(double v, double center, float inflate) {
        if (v - center > 1e-9) return (float) (v + inflate);
        if (v - center < -1e-9) return (float) (v - inflate);
        return (float) v;
    }
}
