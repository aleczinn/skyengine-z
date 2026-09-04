package de.skyengine.game.physics;

import java.util.Objects;

/**
 * Clips horizontal player movement at the boundary of unavailable chunk columns.
 * Missing replicated terrain is therefore never treated as air, while movement back
 * into already available terrain and movement parallel to the frontier remain possible.
 */
public final class ChunkMovementLimiter {
    public static final int CHUNK_SHIFT = 5;
    public static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    private static final double EPSILON = 1.0E-7;

    @FunctionalInterface
    public interface Availability {
        Availability ALL = (chunkX, chunkZ) -> true;
        boolean isAvailable(int chunkX, int chunkZ);
    }

    public record Movement(double x, double y, double z) { }

    private ChunkMovementLimiter() { }

    /** Axis-order matches entity collision (X before Z after the vertical pass). */
    public static Movement limit(AABB box, double dx, double dy, double dz,
                                 Availability availability) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(availability, "availability");
        if (availability == Availability.ALL) return new Movement(dx, dy, dz);

        int minChunkX = chunk(box.minX + EPSILON);
        int maxChunkX = chunk(box.maxX - EPSILON);
        int minChunkZ = chunk(box.minZ + EPSILON);
        int maxChunkZ = chunk(box.maxZ - EPSILON);
        if (!areaAvailable(availability, minChunkX, maxChunkX, minChunkZ, maxChunkZ)) {
            /* A late authoritative unload or reconciliation can leave the predicted box
               straddling the frontier. Do not trap it there: permit only axes which lead into
               an available edge column/row. Continuing farther into missing terrain remains
               blocked, while the player can always retreat to the resident side. */
            boolean recoverNegativeX = columnAvailable(availability, minChunkX, minChunkZ, maxChunkZ)
                    || columnAvailable(availability, minChunkX - 1, minChunkZ, maxChunkZ);
            boolean recoverPositiveX = columnAvailable(availability, maxChunkX, minChunkZ, maxChunkZ)
                    || columnAvailable(availability, maxChunkX + 1, minChunkZ, maxChunkZ);
            boolean recoverNegativeZ = rowAvailable(availability, minChunkZ, minChunkX, maxChunkX)
                    || rowAvailable(availability, minChunkZ - 1, minChunkX, maxChunkX);
            boolean recoverPositiveZ = rowAvailable(availability, maxChunkZ, minChunkX, maxChunkX)
                    || rowAvailable(availability, maxChunkZ + 1, minChunkX, maxChunkX);
            double recoveryX = ((dx < 0 && recoverNegativeX) || (dx > 0 && recoverPositiveX)) ? dx : 0;
            double recoveryZ = ((dz < 0 && recoverNegativeZ) || (dz > 0 && recoverPositiveZ)) ? dz : 0;
            return new Movement(recoveryX, dy, recoveryZ);
        }

        double limitedX = limitX(box, dx, availability, minChunkZ, maxChunkZ);
        double movedMinX = box.minX + limitedX;
        double movedMaxX = box.maxX + limitedX;
        double limitedZ = limitZ(box, dz, availability,
                chunk(movedMinX + EPSILON), chunk(movedMaxX - EPSILON));
        return new Movement(limitedX, dy, limitedZ);
    }

    private static double limitX(AABB box, double dx, Availability availability,
                                 int minChunkZ, int maxChunkZ) {
        if (dx > 0) {
            int current = chunk(box.maxX - EPSILON);
            int target = chunk(box.maxX + dx - EPSILON);
            for (int chunkX = current + 1; chunkX <= target; chunkX++) {
                if (!columnAvailable(availability, chunkX, minChunkZ, maxChunkZ)) {
                    return Math.max(0, chunkX * (double) CHUNK_SIZE - box.maxX - EPSILON);
                }
            }
        } else if (dx < 0) {
            int current = chunk(box.minX + EPSILON);
            int target = chunk(box.minX + dx + EPSILON);
            for (int chunkX = current - 1; chunkX >= target; chunkX--) {
                if (!columnAvailable(availability, chunkX, minChunkZ, maxChunkZ)) {
                    double boundary = (chunkX + 1) * (double) CHUNK_SIZE;
                    return Math.min(0, boundary - box.minX + EPSILON);
                }
            }
        }
        return dx;
    }

    private static double limitZ(AABB box, double dz, Availability availability,
                                 int minChunkX, int maxChunkX) {
        if (dz > 0) {
            int current = chunk(box.maxZ - EPSILON);
            int target = chunk(box.maxZ + dz - EPSILON);
            for (int chunkZ = current + 1; chunkZ <= target; chunkZ++) {
                if (!rowAvailable(availability, chunkZ, minChunkX, maxChunkX)) {
                    return Math.max(0, chunkZ * (double) CHUNK_SIZE - box.maxZ - EPSILON);
                }
            }
        } else if (dz < 0) {
            int current = chunk(box.minZ + EPSILON);
            int target = chunk(box.minZ + dz + EPSILON);
            for (int chunkZ = current - 1; chunkZ >= target; chunkZ--) {
                if (!rowAvailable(availability, chunkZ, minChunkX, maxChunkX)) {
                    double boundary = (chunkZ + 1) * (double) CHUNK_SIZE;
                    return Math.min(0, boundary - box.minZ + EPSILON);
                }
            }
        }
        return dz;
    }

    private static boolean areaAvailable(Availability availability, int minX, int maxX,
                                         int minZ, int maxZ) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (!availability.isAvailable(x, z)) return false;
            }
        }
        return true;
    }

    private static boolean columnAvailable(Availability availability, int chunkX,
                                           int minChunkZ, int maxChunkZ) {
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            if (!availability.isAvailable(chunkX, chunkZ)) return false;
        }
        return true;
    }

    private static boolean rowAvailable(Availability availability, int chunkZ,
                                        int minChunkX, int maxChunkX) {
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            if (!availability.isAvailable(chunkX, chunkZ)) return false;
        }
        return true;
    }

    private static int chunk(double coordinate) {
        return (int) Math.floor(coordinate) >> CHUNK_SHIFT;
    }
}
