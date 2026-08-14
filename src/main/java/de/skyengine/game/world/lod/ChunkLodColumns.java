package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.LodFeatureBuffer;

import java.util.ArrayList;

/** Kompakte L0-L5-Spalten eines 32x32-Chunks; Level werden nur bei Bedarf materialisiert. */
public final class ChunkLodColumns {

    public static final int LEVELS = 6;
    record GeneratedBuild(ChunkLodColumns columns, long terrainNanos,
                          long projectionNanos, long reductionNanos) {}
    private final LodColumn[][] levels = new LodColumn[LEVELS][];

    private ChunkLodColumns() {}

    static ChunkLodColumns fromLevels(LodColumn[][] levels) {
        ChunkLodColumns result = new ChunkLodColumns();
        for (int level = 0; level < LEVELS; level++) {
            if (levels[level] == null) continue;
            int side = ChunkSection.SIZE >> level;
            if (levels[level].length != side * side) {
                throw new IllegalArgumentException("Ungueltige LOD-Level-Laenge");
            }
            result.levels[level] = levels[level];
        }
        return result;
    }

    /** Exakter Snapshot-Pfad: scannt den Chunk einmal und behaelt nur das angeforderte Level. */
    public static ChunkLodColumns fromChunk(Chunk chunk, int requestedLevel) {
        checkLevel(requestedLevel);
        LodColumn[] current = new LodColumn[ChunkSection.SIZE * ChunkSection.SIZE];
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) current[z * ChunkSection.SIZE + x] = scan(chunk, x, z);
        }
        for (int level = 1; level <= requestedLevel; level++) current = reduceLevel(current, level);
        ChunkLodColumns result = new ChunkLodColumns();
        result.levels[requestedLevel] = current;
        return result;
    }

    /**
     * Schneller Hybrid fuer nie gespeicherte Weltbereiche: direktes Terrain-Sampling plus
     * derselbe deterministische Feature-Pass in einem sparsamen Puffer.
     */
    public static ChunkLodColumns fromGenerator(WorldGenerator generator, LodFeatureBuffer features,
                                                 int chunkX, int chunkZ, int requestedLevel) {
        return buildFromGenerator(generator, features, chunkX, chunkZ, requestedLevel).columns;
    }

    static GeneratedBuild buildFromGenerator(WorldGenerator generator, LodFeatureBuffer features,
                                              int chunkX, int chunkZ, int requestedLevel) {
        checkLevel(requestedLevel);
        long terrainStarted = System.nanoTime();
        int side = ChunkSection.SIZE >> requestedLevel;
        int size = 1 << requestedLevel;
        int cellArea = size * size;
        LodColumn[] natural = new LodColumn[side * side];
        int baseX = chunkX << ChunkSection.SHIFT, baseZ = chunkZ << ChunkSection.SHIFT;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int wx = baseX + (x << requestedLevel) + (size >> 1);
                int wz = baseZ + (z << requestedLevel) + (size >> 1);
                natural[z * side + x] = naturalColumn(generator, wx, wz, cellArea);
            }
        }
        long terrainNanos = System.nanoTime() - terrainStarted;

        long projectionStarted = System.nanoTime();
        @SuppressWarnings("unchecked")
        ArrayList<Long>[] featureRuns = new ArrayList[ChunkSection.SIZE * ChunkSection.SIZE];
        features.forEach((x, y, z, state) -> {
            int index = z * ChunkSection.SIZE + x;
            ArrayList<Long> runs = featureRuns[index];
            if (runs == null) featureRuns[index] = runs = new ArrayList<>();
            runs.add(LodColumn.pack(LodBlockRules.simplify(state), y, y + 1, LodColumn.FLAG_LANDMARK));
        });
        LodColumn[] featureColumns = new LodColumn[featureRuns.length];
        for (int i = 0; i < featureColumns.length; i++) {
            ArrayList<Long> runs = featureRuns[i];
            if (runs == null) featureColumns[i] = LodColumn.EMPTY;
            else {
                runs.sort(java.util.Comparator.comparingInt(LodColumn::minY));
                featureColumns[i] = LodColumnReducer.limitIntervals(mergeAdjacent(runs));
            }
        }
        long projectionNanos = System.nanoTime() - projectionStarted;
        long reductionStarted = System.nanoTime();
        for (int level = 1; level <= requestedLevel; level++) featureColumns = reduceLevel(featureColumns, level);
        for (int i = 0; i < natural.length; i++) natural[i] = merge(natural[i], featureColumns[i]);

        ChunkLodColumns result = new ChunkLodColumns();
        result.levels[requestedLevel] = natural;
        return new GeneratedBuild(result, terrainNanos, projectionNanos,
                System.nanoTime() - reductionStarted);
    }

    public synchronized void merge(ChunkLodColumns other) {
        for (int level = 0; level < LEVELS; level++) {
            if (other.levels[level] != null) this.levels[level] = other.levels[level];
        }
    }

    public synchronized boolean hasLevel(int level) {
        return this.levels[level] != null;
    }

    public LodColumn get(int localX, int localZ, int size) {
        int level = levelForSize(size);
        LodColumn[] data = this.levels[level];
        if (data == null) throw new IllegalStateException("LOD-Level L" + level + " wurde nicht geladen");
        int side = ChunkSection.SIZE >> level;
        return data[(localZ >> level) * side + (localX >> level)];
    }

    synchronized int levelMask() {
        int mask = 0;
        for (int level = 0; level < LEVELS; level++) if (this.levels[level] != null) mask |= 1 << level;
        return mask;
    }

    synchronized LodColumn[] level(int level) { return this.levels[level]; }

    synchronized long estimatedBytes() {
        long bytes = 64;
        for (LodColumn[] level : this.levels) {
            if (level == null) continue;
            bytes += 16L + 8L * level.length;
            for (LodColumn column : level) bytes += 24L + 8L * column.size();
        }
        return bytes;
    }

    private static LodColumn naturalColumn(WorldGenerator generator, int wx, int wz, int coverage) {
        WorldGenerator.LodSurfaces surfaces = generator.sampleLodSurfaces(wx, wz);
        long ground = surfaces.ground();
        long surface = surfaces.surface();
        int groundState = LodBlockRules.simplify(LodDataSource.block(ground));
        int groundTop = Math.max(0, Math.min(Chunk.HEIGHT, LodDataSource.height(ground) + 1));
        int surfaceState = LodBlockRules.simplify(LodDataSource.block(surface));
        int surfaceTop = Math.max(groundTop, Math.min(Chunk.HEIGHT, LodDataSource.height(surface) + 1));
        ArrayList<Long> runs = new ArrayList<>(2);
        if (groundState != Blocks.AIR && groundTop > 0) {
            runs.add(LodColumn.pack(groundState, 0, groundTop, 0, coverage));
        }
        if (surfaceState != Blocks.AIR && surfaceTop > groundTop) {
            runs.add(LodColumn.pack(surfaceState, groundTop, surfaceTop,
                    LodColumn.FLAG_SKY_OPEN, coverage));
        }
        return runs.isEmpty() ? LodColumn.EMPTY : new LodColumn(runs.stream().mapToLong(Long::longValue).toArray());
    }

    private static LodColumn[] reduceLevel(LodColumn[] children, int level) {
        int side = ChunkSection.SIZE >> level;
        int childSide = side << 1;
        LodColumn[] parents = new LodColumn[side * side];
        LodColumn[] group = new LodColumn[4];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int child = (z << 1) * childSide + (x << 1);
                group[0] = children[child]; group[1] = children[child + 1];
                group[2] = children[child + childSide]; group[3] = children[child + childSide + 1];
                if (group[0].size() == 0 && group[1].size() == 0
                        && group[2].size() == 0 && group[3].size() == 0) {
                    parents[z * side + x] = LodColumn.EMPTY;
                } else {
                    parents[z * side + x] = LodColumnReducer.reduce(group, 1 << (level << 1));
                }
            }
        }
        return parents;
    }

    private static LodColumn merge(LodColumn natural, LodColumn feature) {
        if (feature.size() == 0) return natural;
        ArrayList<Long> intervals = new ArrayList<>(natural.size() + feature.size());
        for (int i = 0; i < natural.size(); i++) intervals.add(natural.interval(i));
        int terrainTop = natural.size() == 0 ? 0 : LodColumn.maxY(natural.interval(natural.size() - 1));
        for (int i = 0; i < feature.size(); i++) {
            long interval = feature.interval(i);
            int minY = Math.max(terrainTop, LodColumn.minY(interval));
            if (minY < LodColumn.maxY(interval)) {
                intervals.add(LodColumn.pack(LodColumn.state(interval), minY,
                        LodColumn.maxY(interval), LodColumn.flags(interval), LodColumn.coverage(interval)));
            }
        }
        intervals.sort(java.util.Comparator.comparingInt(LodColumn::minY));
        return LodColumnReducer.limitIntervals(intervals);
    }

    private static ArrayList<Long> mergeAdjacent(ArrayList<Long> sorted) {
        ArrayList<Long> merged = new ArrayList<>(sorted.size());
        for (long interval : sorted) {
            if (!merged.isEmpty()) {
                long previous = merged.getLast();
                if (LodColumn.state(previous) == LodColumn.state(interval)
                        && LodColumn.maxY(previous) == LodColumn.minY(interval)) {
                    merged.set(merged.size() - 1, LodColumn.pack(LodColumn.state(previous),
                            LodColumn.minY(previous), LodColumn.maxY(interval), LodColumn.FLAG_LANDMARK,
                            Math.max(LodColumn.coverage(previous), LodColumn.coverage(interval))));
                    continue;
                }
            }
            merged.add(interval);
        }
        return merged;
    }

    private static LodColumn scan(Chunk chunk, int x, int z) {
        ArrayList<Long> runs = new ArrayList<>();
        int state = Blocks.AIR, start = 0;
        for (int sectionIndex = 0; sectionIndex < Chunk.SECTIONS; sectionIndex++) {
            int baseY = sectionIndex << ChunkSection.SHIFT;
            ChunkSection section = chunk.getSection(sectionIndex);
            if (section == null || section.isEmpty()) {
                if (state != Blocks.AIR) runs.add(LodColumn.pack(state, start, baseY, 0));
                state = Blocks.AIR;
                start = baseY;
                continue;
            }
            PalettedContainer container = section.container();
            if (container.isSingleValue()) {
                int next = LodBlockRules.simplify(container.singleValue());
                if (next != state) {
                    if (state != Blocks.AIR) runs.add(LodColumn.pack(state, start, baseY, 0));
                    state = next;
                    start = baseY;
                }
                continue;
            }
            for (int localY = 0; localY < ChunkSection.SIZE; localY++) {
                int y = baseY + localY;
                int next = LodBlockRules.simplify(section.getBlock(x, localY, z));
                if (next == state) continue;
                if (state != Blocks.AIR) runs.add(LodColumn.pack(state, start, y, 0));
                state = next;
                start = y;
            }
        }
        if (state != Blocks.AIR) runs.add(LodColumn.pack(state, start, Chunk.HEIGHT, 0));
        return LodColumnReducer.limitIntervals(runs);
    }

    private static int levelForSize(int size) {
        if (size <= 0 || size > ChunkSection.SIZE || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("Ungueltige LOD-Zellgroesse: " + size);
        }
        return Integer.numberOfTrailingZeros(size);
    }

    private static void checkLevel(int level) {
        if (level < 0 || level >= LEVELS) throw new IllegalArgumentException("Ungueltiges LOD-Level: " + level);
    }
}
