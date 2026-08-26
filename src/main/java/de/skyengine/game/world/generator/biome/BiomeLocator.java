package de.skyengine.game.world.generator.biome;

import de.skyengine.game.world.generator.WorldGenerator;

import java.util.Locale;

/** Pure, chunk-unabhaengige Suche im Biome-Sampler eines Generators. */
public final class BiomeLocator {

    public static final int DEFAULT_RADIUS = 6400;
    public static final int DEFAULT_STEP = 32;

    public record Result(int x, int z, int distance) {}

    public static Biome byName(String name) {
        if (name == null) return null;
        String normalized = name.toLowerCase(Locale.ROOT);
        for (Biome biome : Biomes.ALL) {
            if (biome.name.equals(normalized)) return biome;
        }
        return null;
    }

    /** Sucht ringweise; Unterbrechung liefert null und wird nicht als Trefferfehler behandelt. */
    public static Result locate(WorldGenerator generator, Biome target, int originX, int originZ,
                                int radius, int step) {
        if (generator == null || target == null || radius < 0 || step < 1) {
            throw new IllegalArgumentException("Ungueltige Biomsuche");
        }
        if (generator.biomeAt(originX, originZ) == target) return new Result(originX, originZ, 0);

        int rings = Math.ceilDiv(radius, step);
        for (int ring = 1; ring <= rings; ring++) {
            if (Thread.currentThread().isInterrupted()) return null;
            Result nearest = null;
            long nearestSquared = Long.MAX_VALUE;
            for (int gx = -ring; gx <= ring; gx++) {
                nearest = sample(generator, target, originX, originZ, gx, -ring, step,
                        radius, nearest, nearestSquared);
                if (nearest != null) nearestSquared = squared(originX, originZ, nearest.x, nearest.z);
                nearest = sample(generator, target, originX, originZ, gx, ring, step,
                        radius, nearest, nearestSquared);
                if (nearest != null) nearestSquared = squared(originX, originZ, nearest.x, nearest.z);
            }
            for (int gz = -ring + 1; gz < ring; gz++) {
                nearest = sample(generator, target, originX, originZ, -ring, gz, step,
                        radius, nearest, nearestSquared);
                if (nearest != null) nearestSquared = squared(originX, originZ, nearest.x, nearest.z);
                nearest = sample(generator, target, originX, originZ, ring, gz, step,
                        radius, nearest, nearestSquared);
                if (nearest != null) nearestSquared = squared(originX, originZ, nearest.x, nearest.z);
            }
            if (nearest != null) return nearest;
        }
        return null;
    }

    private static Result sample(WorldGenerator generator, Biome target, int originX, int originZ,
                                 int gridX, int gridZ, int step, int radius,
                                 Result current, long currentSquared) {
        int dx = gridX * step, dz = gridZ * step;
        long squared = (long) dx * dx + (long) dz * dz;
        if (squared > (long) radius * radius || squared >= currentSquared) return current;
        int x = originX + dx, z = originZ + dz;
        if (generator.biomeAt(x, z) != target) return current;
        return new Result(x, z, (int) Math.round(Math.sqrt(squared)));
    }

    private static long squared(int originX, int originZ, int x, int z) {
        long dx = (long) x - originX, dz = (long) z - originZ;
        return dx * dx + dz * dz;
    }

    private BiomeLocator() {}
}
