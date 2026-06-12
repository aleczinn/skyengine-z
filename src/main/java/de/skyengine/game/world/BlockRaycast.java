package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import org.joml.Vector3d;

public final class BlockRaycast {

    /**
     * @param x/y/z   Position des getroffenen Blocks
     * @param block   Block-ID
     * @param faceX/Y/Z Normale der getroffenen Seite (z.B. 0,1,0 = Oberseite).
     *                Dort wird beim Platzieren der neue Block gesetzt.
     */
    public record Hit(int x, int y, int z, short block, int faceX, int faceY, int faceZ) {}

    private BlockRaycast() {}

    /**
     * Amanatides & Woo Voxel-Traversal. Läuft den Strahl Block für Block ab.
     *
     * @param origin      Startpunkt (Augenposition der Kamera)
     * @param dir         normalisierte Blickrichtung
     * @param maxDistance Reichweite in Blöcken
     * @return Hit oder null, wenn nichts getroffen wurde
     */
    public static Hit raycast(World world, Vector3d origin, Vector3d dir, double maxDistance) {
        int x = (int) Math.floor(origin.x);
        int y = (int) Math.floor(origin.y);
        int z = (int) Math.floor(origin.z);

        /* Schrittrichtung pro Achse */
        int stepX = dir.x > 0 ? 1 : -1;
        int stepY = dir.y > 0 ? 1 : -1;
        int stepZ = dir.z > 0 ? 1 : -1;

        /* tMax: Strahl-Distanz bis zur nächsten Voxelgrenze pro Achse
           tDelta: Strahl-Distanz, um einen ganzen Voxel auf dieser Achse zu durchqueren */
        double tMaxX = intBound(origin.x, dir.x);
        double tMaxY = intBound(origin.y, dir.y);
        double tMaxZ = intBound(origin.z, dir.z);

        double tDeltaX = dir.x != 0 ? Math.abs(1.0 / dir.x) : Double.POSITIVE_INFINITY;
        double tDeltaY = dir.y != 0 ? Math.abs(1.0 / dir.y) : Double.POSITIVE_INFINITY;
        double tDeltaZ = dir.z != 0 ? Math.abs(1.0 / dir.z) : Double.POSITIVE_INFINITY;

        int faceX = 0, faceY = 0, faceZ = 0;

        while (true) {
            short block = world.getBlock(x, y, z);
            if (block != Blocks.AIR) {
                /* face ist (0,0,0) wenn die Kamera IM Block startet - Treffer trotzdem melden */
                return new Hit(x, y, z, block, faceX, faceY, faceZ);
            }

            /* Zur nächsten Voxelgrenze: die Achse mit dem kleinsten tMax gewinnt */
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX > maxDistance) return null;
                    x += stepX;
                    tMaxX += tDeltaX;
                    faceX = -stepX; faceY = 0; faceZ = 0;
                } else {
                    if (tMaxZ > maxDistance) return null;
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                    faceX = 0; faceY = 0; faceZ = -stepZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    if (tMaxY > maxDistance) return null;
                    y += stepY;
                    tMaxY += tDeltaY;
                    faceX = 0; faceY = -stepY; faceZ = 0;
                } else {
                    if (tMaxZ > maxDistance) return null;
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                    faceX = 0; faceY = 0; faceZ = -stepZ;
                }
            }
        }
    }

    /** Strahl-Distanz von s bis zur nächsten ganzzahligen Grenze in Richtung ds. */
    private static double intBound(double s, double ds) {
        if (ds == 0) return Double.POSITIVE_INFINITY;
        if (ds > 0) {
            return (Math.floor(s) + 1.0 - s) / ds;
        }
        return (s - Math.floor(s)) / -ds;
    }
}