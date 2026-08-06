package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Direction;
import org.joml.Vector3d;

import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Eine Kollisions-/Umriss-Form eines BlockStates: eine Menge achsenparalleler
 * Boxen in LOKALEN Blockkoordinaten (0..1). Wird für Entity-Kollision,
 * Raycasting und die Selection-Box gemeinsam genutzt.
 */
public final class BlockShape {

    public static final BlockShape EMPTY = new BlockShape(new AABB[0]);
    public static final BlockShape FULL_CUBE = new BlockShape(new AABB[]{new AABB(0, 0, 0, 1, 1, 1)});

    private final AABB[] boxes;
    private final boolean fullCube;
    private final int fullFaceMask;

    public BlockShape(AABB[] boxes) {
        this.boxes = boxes;
        this.fullCube = coversUnitCube(boxes);
        int faces = 0;
        for (Direction direction : Direction.sharedValues()) {
            if (coversFace(boxes, direction)) faces |= 1 << direction.faceIndex();
        }
        this.fullFaceMask = faces;
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

    /** true, wenn die Kollisionsform den ganzen Blockraum 0..1 belegt. */
    public boolean isFullCube() {
        return this.fullCube;
    }

    /**
     * true, wenn die Vereinigung der Kollisionsboxen die komplette angegebene Blockseite
     * bedeckt. Entspricht fuer die hier achsenparallelen Shapes Vanillas
     * {@code isFaceSturdy(..., SupportType.FULL)} und beruecksichtigt auch zusammengesetzte
     * Flaechen, etwa die Rueckseite einer Treppe.
     */
    public boolean isFaceFull(Direction direction) {
        return (this.fullFaceMask & 1 << direction.faceIndex()) != 0;
    }

    /**
     * Prüft auch zusammengesetzte Formen. Vanillas {@code isCollisionShapeFullBlock}
     * betrachtet die Vereinigung aller Teilboxen, nicht nur eine einzelne Vollwürfel-Box.
     */
    private static boolean coversUnitCube(AABB[] boxes) {
        if (boxes.length == 0) return false;
        NavigableSet<Double> xs = boundaries(boxes, 0);
        NavigableSet<Double> ys = boundaries(boxes, 1);
        NavigableSet<Double> zs = boundaries(boxes, 2);
        Double[] xa = xs.toArray(Double[]::new);
        Double[] ya = ys.toArray(Double[]::new);
        Double[] za = zs.toArray(Double[]::new);

        for (int xi = 0; xi < xa.length - 1; xi++) {
            double x = (xa[xi] + xa[xi + 1]) * 0.5;
            for (int yi = 0; yi < ya.length - 1; yi++) {
                double y = (ya[yi] + ya[yi + 1]) * 0.5;
                for (int zi = 0; zi < za.length - 1; zi++) {
                    double z = (za[zi] + za[zi + 1]) * 0.5;
                    boolean covered = false;
                    for (AABB box : boxes) {
                        if (box.minX <= x && box.maxX >= x
                                && box.minY <= y && box.maxY >= y
                                && box.minZ <= z && box.maxZ >= z) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) return false;
                }
            }
        }
        return true;
    }

    private static boolean coversFace(AABB[] boxes, Direction face) {
        NavigableSet<Double> first = new TreeSet<>();
        NavigableSet<Double> second = new TreeSet<>();
        first.add(0.0);
        first.add(1.0);
        second.add(0.0);
        second.add(1.0);
        int firstAxis = face.axis() == Direction.Axis.X ? 1 : 0;
        int secondAxis = face.axis() == Direction.Axis.Z ? 1 : 2;
        if (face.axis() == Direction.Axis.Y) secondAxis = 2;

        boolean hasTouchingBox = false;
        for (AABB box : boxes) {
            if (!touchesFace(box, face)) continue;
            hasTouchingBox = true;
            addBoundary(first, coordinate(box, firstAxis, false));
            addBoundary(first, coordinate(box, firstAxis, true));
            addBoundary(second, coordinate(box, secondAxis, false));
            addBoundary(second, coordinate(box, secondAxis, true));
        }
        if (!hasTouchingBox) return false;

        Double[] firstValues = first.toArray(Double[]::new);
        Double[] secondValues = second.toArray(Double[]::new);
        for (int a = 0; a < firstValues.length - 1; a++) {
            double firstMid = (firstValues[a] + firstValues[a + 1]) * 0.5;
            for (int b = 0; b < secondValues.length - 1; b++) {
                double secondMid = (secondValues[b] + secondValues[b + 1]) * 0.5;
                boolean covered = false;
                for (AABB box : boxes) {
                    if (!touchesFace(box, face)) continue;
                    if (coordinate(box, firstAxis, false) <= firstMid
                            && coordinate(box, firstAxis, true) >= firstMid
                            && coordinate(box, secondAxis, false) <= secondMid
                            && coordinate(box, secondAxis, true) >= secondMid) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) return false;
            }
        }
        return true;
    }

    private static boolean touchesFace(AABB box, Direction face) {
        return switch (face) {
            case DOWN -> box.minY <= 0;
            case UP -> box.maxY >= 1;
            case NORTH -> box.minZ <= 0;
            case SOUTH -> box.maxZ >= 1;
            case WEST -> box.minX <= 0;
            case EAST -> box.maxX >= 1;
        };
    }

    private static double coordinate(AABB box, int axis, boolean max) {
        return switch (axis) {
            case 0 -> max ? box.maxX : box.minX;
            case 1 -> max ? box.maxY : box.minY;
            default -> max ? box.maxZ : box.minZ;
        };
    }

    private static void addBoundary(NavigableSet<Double> boundaries, double value) {
        if (value > 0 && value < 1) boundaries.add(value);
    }

    private static NavigableSet<Double> boundaries(AABB[] boxes, int axis) {
        NavigableSet<Double> values = new TreeSet<>();
        values.add(0.0);
        values.add(1.0);
        for (AABB box : boxes) {
            double min = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
            double max = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
            if (min > 0 && min < 1) values.add(min);
            if (max > 0 && max < 1) values.add(max);
        }
        return values;
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
