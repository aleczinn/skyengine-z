package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;
import org.joml.Vector3d;

/**
 * Eine Kollisions-/Umriss-Form eines BlockStates: eine Menge achsenparalleler
 * Boxen in LOKALEN Blockkoordinaten (0..1). Wird für Entity-Kollision,
 * Raycasting und die Selection-Box gemeinsam genutzt.
 */
public final class BlockShape {

    public static final BlockShape EMPTY = new BlockShape(new AABB[0]);
    public static final BlockShape FULL_CUBE = new BlockShape(new AABB[]{new AABB(0, 0, 0, 1, 1, 1)});

    private final AABB[] boxes;

    public BlockShape(AABB[] boxes) {
        this.boxes = boxes;
    }

    public static BlockShape box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new BlockShape(new AABB[]{new AABB(x0, y0, z0, x1, y1, z1)});
    }

    public AABB[] boxes() {
        return boxes;
    }

    public boolean isEmpty() {
        return boxes.length == 0;
    }

    /** Treffer eines Strahls: Distanz t entlang dir + Normale der getroffenen Fläche. */
    public record RayHit(double t, int faceX, int faceY, int faceZ) {}

    /**
     * Schneidet einen Strahl gegen alle Boxen dieser Form (an Blockposition
     * bx/by/bz) und liefert den nächsten Treffer oder null.
     *
     * @param origin Strahlursprung (Weltkoordinaten)
     * @param dir    normalisierte Richtung
     */
    public RayHit clip(Vector3d origin, Vector3d dir, int bx, int by, int bz) {
        double bestT = Double.POSITIVE_INFINITY;
        int fx = 0, fy = 0, fz = 0;
        boolean found = false;

        for (AABB local : boxes) {
            double minX = local.minX + bx, minY = local.minY + by, minZ = local.minZ + bz;
            double maxX = local.maxX + bx, maxY = local.maxY + by, maxZ = local.maxZ + bz;

            double tmin = Double.NEGATIVE_INFINITY;
            double tmax = Double.POSITIVE_INFINITY;
            int axis = -1, sign = 0;
            boolean miss = false;

            for (int a = 0; a < 3; a++) {
                double o = a == 0 ? origin.x : a == 1 ? origin.y : origin.z;
                double d = a == 0 ? dir.x : a == 1 ? dir.y : dir.z;
                double lo = a == 0 ? minX : a == 1 ? minY : minZ;
                double hi = a == 0 ? maxX : a == 1 ? maxY : maxZ;

                if (Math.abs(d) < 1e-9) {
                    if (o < lo || o > hi) { miss = true; break; }
                    continue;
                }
                double inv = 1.0 / d;
                double tNear = (lo - o) * inv;
                double tFar = (hi - o) * inv;
                int s = -1; // Eintritt über die Min-Ebene -> Normale zeigt in -Achse
                if (tNear > tFar) { double tmp = tNear; tNear = tFar; tFar = tmp; s = 1; }
                if (tNear > tmin) { tmin = tNear; axis = a; sign = s; }
                if (tFar < tmax) tmax = tFar;
                if (tmin > tmax) { miss = true; break; }
            }
            if (miss || tmax < 0) continue;

            /* Strahl startet innerhalb der Box -> Sofort-Treffer ohne Face */
            if (tmin < 0) {
                if (0 < bestT) { bestT = 0; fx = fy = fz = 0; found = true; }
                continue;
            }
            if (tmin < bestT) {
                bestT = tmin;
                fx = axis == 0 ? sign : 0;
                fy = axis == 1 ? sign : 0;
                fz = axis == 2 ? sign : 0;
                found = true;
            }
        }

        return found ? new RayHit(bestT, fx, fy, fz) : null;
    }
}
