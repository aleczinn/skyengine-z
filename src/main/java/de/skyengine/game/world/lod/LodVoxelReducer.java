package de.skyengine.game.world.lod;

/** Deterministischer 2x2x2-Reducer fuer kanonische volumetrische LOD-Knoten. */
public final class LodVoxelReducer {

    /** Reduziert acht gleichstufige Kindknoten; Reihenfolge: x | z&lt;&lt;1 | y&lt;&lt;2. */
    public static LodVoxelSection reduce(LodVoxelSection[] children) {
        if (children == null || children.length != 8) throw new IllegalArgumentException("Acht Kinder erforderlich");
        LodVoxelSection first = children[0];
        if (first == null || first.level >= LodVoxelSection.MAX_LEVEL) {
            throw new IllegalArgumentException("Ungueltiges erstes Kind");
        }
        boolean canonical = true;
        for (int i = 0; i < 8; i++) {
            LodVoxelSection child = children[i];
            if (child == null || child.level != first.level) throw new IllegalArgumentException("Fehlendes/falsches Kind " + i);
            int ex = first.nodeX + (i & 1), ez = first.nodeZ + (i >>> 1 & 1), ey = first.nodeY + (i >>> 2 & 1);
            if (child.nodeX != ex || child.nodeY != ey || child.nodeZ != ez) {
                throw new IllegalArgumentException("Kinder muessen einen ausgerichteten 2x2x2-Block bilden");
            }
            canonical &= child.completeness() == LodVoxelSection.Completeness.CANONICAL;
        }
        if ((first.nodeX & 1) != 0 || (first.nodeY & 1) != 0 || (first.nodeZ & 1) != 0) {
            throw new IllegalArgumentException("Kind 0 muss gerade Knotenkordinaten besitzen");
        }
        LodVoxelSection parent = new LodVoxelSection(first.nodeX >> 1, first.nodeY >> 1,
                first.nodeZ >> 1, first.level + 1, canonical
                ? LodVoxelSection.Completeness.CANONICAL : LodVoxelSection.Completeness.PROVISIONAL);
        long[] samples = new long[8];
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int n = 0;
            for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                int sx = x * 2 + dx, sy = y * 2 + dy, sz = z * 2 + dz;
                int childIndex = (sx >>> 5) | (sz >>> 5) << 1 | (sy >>> 5) << 2;
                samples[n++] = children[childIndex].get(sx & 31, sy & 31, sz & 31);
            }
            parent.set(x, y, z, reduceCell(samples));
        }
        return parent;
    }

    public static long reduceCell(long[] children) {
        if (children == null || children.length != 8) throw new IllegalArgumentException("Acht Zellen erforderlich");
        int totalCoverage = 0, sky = 0, red = 0, green = 0, blue = 0;
        int bestState = 0, bestScore = -1, bestImportance = 0, bestProvenance = 0;
        for (long child : children) {
            int coverage = LodVoxel.coverage(child);
            totalCoverage += coverage;
            sky += LodVoxel.sky(child) * coverage;
            red += LodVoxel.red(child) * coverage;
            green += LodVoxel.green(child) * coverage;
            blue += LodVoxel.blue(child) * coverage;
            int importance = LodVoxel.importance(child);
            /* Importance ist absichtlich additiv und stark: ein duennes 1-Zellen-Dach, ein
               Baumstamm oder eine Turmspitze darf nicht gegen einen voll gedeckten, aber
               belanglosen Terrain-Voxel verlieren. Bei gleicher Importance entscheidet
               weiterhin die tatsaechliche Bedeckung. */
            int score = coverage + importance * 256;
            if (score > bestScore || score == bestScore && Integer.compareUnsigned(LodVoxel.stateId(child), bestState) < 0) {
                bestScore = score;
                bestState = LodVoxel.stateId(child);
                bestImportance = importance;
            }
            bestProvenance = Math.max(bestProvenance, LodVoxel.provenance(child));
        }
        if (totalCoverage == 0) return 0L;
        int coverage = (totalCoverage + 4) / 8;
        return LodVoxel.pack(bestState, rounded(sky, totalCoverage), rounded(red, totalCoverage),
                rounded(green, totalCoverage), rounded(blue, totalCoverage), coverage,
                bestProvenance, bestImportance);
    }

    private static int rounded(int sum, int weight) { return (sum + weight / 2) / weight; }

    private LodVoxelReducer() {}
}
