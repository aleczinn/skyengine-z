package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.LodFeatureBuffer;

import java.util.ArrayList;
import java.util.List;

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

    /** Exakter Snapshot-Pfad: scannt den Chunk einmal und materialisiert nur das angeforderte Level. */
    public static ChunkLodColumns fromChunk(Chunk chunk, int requestedLevel) {
        return fromChunk(chunk, null, requestedLevel);
    }

    /** Exakter Snapshot mit optionaler natuerlicher Terrainhuelle fuer Generatorwelten. */
    public static ChunkLodColumns fromChunk(Chunk chunk, WorldGenerator generator, int requestedLevel) {
        checkLevel(requestedLevel);
        int baseX = chunk.chunkX << ChunkSection.SHIFT;
        int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
        LodColumn[] current = new LodColumn[ChunkSection.SIZE * ChunkSection.SIZE];
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                ArrayList<Long> runs = scanRuns(chunk, x, z);
                current[z * ChunkSection.SIZE + x] = generator == null
                        ? LodColumnReducer.limitIntervals(runs)
                        : normalizeNaturalShell(runs, generator, baseX + x, baseZ + z, 1);
            }
        }
        ChunkLodColumns result = new ChunkLodColumns();
        for (int level = 1; level <= requestedLevel; level++) {
            current = reduceLevel(current, level);
        }
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
        int baseX = chunkX << ChunkSection.SHIFT, baseZ = chunkZ << ChunkSection.SHIFT;
        long projectionStarted = System.nanoTime();
        @SuppressWarnings("unchecked")
        ArrayList<Long>[] featureRuns = new ArrayList[ChunkSection.SIZE * ChunkSection.SIZE];
        features.forEach((x, y, z, state) -> {
            int index = z * ChunkSection.SIZE + x;
            ArrayList<Long> runs = featureRuns[index];
            if (runs == null) featureRuns[index] = runs = new ArrayList<>();
            int flags = LodColumn.FLAG_LANDMARK;
            if (features.isSupport(x, y, z)) flags |= LodColumn.FLAG_SUPPORT;
            runs.add(LodColumn.pack(LodBlockRules.simplify(state), y, y + 1, flags));
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
        long terrainNanos = 0;
        long reductionNanos = 0;
        for (int level = 1; level <= requestedLevel; level++) {
            long reductionStarted = System.nanoTime();
            featureColumns = reduceLevel(featureColumns, level, false);
            reductionNanos += System.nanoTime() - reductionStarted;
        }
        long terrainStarted = System.nanoTime();
        int side = ChunkSection.SIZE >> requestedLevel;
        int size = 1 << requestedLevel;
        int cellArea = size * size;
        LodColumn[] natural = new LodColumn[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int wx = baseX + (x << requestedLevel) + (size >> 1);
                int wz = baseZ + (z << requestedLevel) + (size >> 1);
                int index = z * side + x;
                natural[index] = merge(naturalColumn(generator, wx, wz, cellArea),
                        featureColumns[index]);
            }
        }
        terrainNanos = System.nanoTime() - terrainStarted;
        ChunkLodColumns result = new ChunkLodColumns();
        result.levels[requestedLevel] = natural;
        return new GeneratedBuild(result, terrainNanos, projectionNanos, reductionNanos);
    }

    public synchronized void merge(ChunkLodColumns other) {
        for (int level = 0; level < LEVELS; level++) {
            if (other.levels[level] != null) this.levels[level] = other.levels[level];
        }
    }

    public synchronized boolean hasLevel(int level) {
        return this.levels[level] != null;
    }

    /**
     * Leitet ein groberes Level ausschliesslich aus bereits vorhandenen kanonischen Kinddaten ab.
     * Dadurch kostet ein spaeterer L3-Abruf nach L1 weder Generator- noch Feature-Arbeit.
     */
    public synchronized boolean materializeLevel(int level) {
        checkLevel(level);
        if (this.levels[level] != null) return true;
        int source = level - 1;
        while (source >= 0 && this.levels[source] == null) source--;
        if (source < 0) return false;
        LodColumn[] current = this.levels[source];
        for (int next = source + 1; next <= level; next++) {
            current = reduceLevel(current, next);
            this.levels[next] = current;
        }
        return true;
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
        int bottomState = LodBlockRules.simplify(generator.lodWorldBottomState());
        ArrayList<Long> runs = new ArrayList<>(3);
        int groundBottom = 0;
        if (bottomState != Blocks.AIR && groundTop > 0) {
            runs.add(LodColumn.pack(bottomState, 0, 1, LodColumn.FLAG_TERRAIN, coverage));
            groundBottom = 1;
        }
        if (groundState != Blocks.AIR && groundTop > groundBottom) {
            runs.add(LodColumn.pack(groundState, groundBottom, groundTop,
                    LodColumn.FLAG_TERRAIN, coverage));
        }
        if (surfaceState != Blocks.AIR && surfaceTop > groundTop) {
            runs.add(LodColumn.pack(surfaceState, groundTop, surfaceTop,
                    LodColumn.FLAG_SKY_OPEN, coverage));
        }
        return runs.isEmpty() ? LodColumn.EMPTY : new LodColumn(runs.stream().mapToLong(Long::longValue).toArray());
    }

    /**
     * Entfernt nur vertikal geschlossene Hoehlen aus der natuerlichen Terrainhuelle. Die aktuell
     * hoechste feste Oberflaeche unter dem Generatorniveau bleibt massgeblich: eine von oben
     * offene Grube wird daher nicht wieder aufgefuellt. Getrennte Intervalle darueber bleiben
     * Landmarken. Existierendes Wasser wird ueber offenen Zellen bis zur Terrainhuelle geschlossen.
     */
    static LodColumn normalizeNaturalShell(LodColumn column, WorldGenerator generator,
                                            int wx, int wz, int coverage) {
        if (column.size() == 0) return column;
        ArrayList<Long> intervals = new ArrayList<>(column.size());
        for (int i = 0; i < column.size(); i++) intervals.add(column.interval(i));
        return normalizeNaturalShell(intervals, generator, wx, wz, coverage);
    }

    /**
     * Normalisiert die vollstaendige, noch nicht verlustbehaftet begrenzte L0-Spalte. Dadurch
     * koennen Materialwechsel und Baumintervalle die natuerliche Huelle nicht schon vor ihrer
     * Terrain-Markierung aus dem Vier-Intervall-Budget verdraengen.
     */
    private static LodColumn normalizeNaturalShell(List<Long> intervals, WorldGenerator generator,
                                                    int wx, int wz, int coverage) {
        if (intervals.isEmpty()) return LodColumn.EMPTY;
        WorldGenerator.LodSurfaces surfaces = generator.sampleLodSurfaces(wx, wz);
        int naturalTop = Math.clamp(LodDataSource.height(surfaces.ground()) + 1, 0, Chunk.HEIGHT);
        int bottomState = LodBlockRules.simplify(generator.lodWorldBottomState());
        int shellTop = bottomState == Blocks.AIR ? 0 : 1;
        int shellState = LodBlockRules.simplify(LodDataSource.block(surfaces.ground()));
        int fluidState = Blocks.AIR;
        int fluidTop = 0;

        for (long interval : intervals) {
            int state = LodColumn.state(interval);
            if (Blocks.getState(state).isFluid()) {
                if (LodColumn.maxY(interval) > fluidTop) {
                    fluidTop = LodColumn.maxY(interval);
                    fluidState = state;
                }
                continue;
            }
            if (LodColumn.minY(interval) >= naturalTop) continue;
            int top = Math.min(naturalTop, LodColumn.maxY(interval));
            if (top > shellTop) {
                shellTop = top;
                shellState = state;
            }
        }

        ArrayList<Long> solids = new ArrayList<>();
        if (bottomState != Blocks.AIR && shellTop > 0) {
            solids.add(LodColumn.pack(bottomState, 0, 1, LodColumn.FLAG_TERRAIN, coverage));
        }
        int shellBottom = bottomState == Blocks.AIR ? 0 : 1;
        if (shellState != Blocks.AIR && shellTop > shellBottom) {
            solids.add(LodColumn.pack(shellState, shellBottom, shellTop,
                    LodColumn.FLAG_TERRAIN, coverage));
        }

        for (long interval : intervals) {
            int state = LodColumn.state(interval);
            if (Blocks.getState(state).isFluid() || LodColumn.maxY(interval) <= shellTop) continue;
            int minY = Math.max(shellTop, LodColumn.minY(interval));
            int flags = LodColumn.flags(interval);
            if (minY >= naturalTop || LodColumn.landmark(interval)) {
                flags = flags & ~LodColumn.FLAG_TERRAIN | LodColumn.FLAG_LANDMARK;
            } else {
                flags = flags & ~LodColumn.FLAG_LANDMARK | LodColumn.FLAG_TERRAIN;
            }
            if ((flags & LodColumn.FLAG_LANDMARK) != 0
                    && LodColumn.minY(interval) <= shellTop
                    && !Blocks.getState(state).isLeaves()) {
                flags |= LodColumn.FLAG_SUPPORT;
            }
            solids.add(LodColumn.pack(state, minY, LodColumn.maxY(interval), flags, coverage));
        }
        solids.sort(java.util.Comparator.comparingInt(LodColumn::minY));

        ArrayList<Long> normalized = new ArrayList<>(solids.size() + 2);
        normalized.addAll(solids);
        if (fluidState != Blocks.AIR && fluidTop > shellTop) {
            int cursor = shellTop;
            for (long solid : solids) {
                int minY = LodColumn.minY(solid), maxY = LodColumn.maxY(solid);
                if (maxY <= cursor || minY >= fluidTop) continue;
                if (minY > cursor) normalized.add(LodColumn.pack(fluidState, cursor,
                        Math.min(minY, fluidTop), 0, coverage));
                cursor = Math.max(cursor, maxY);
                if (cursor >= fluidTop) break;
            }
            if (cursor < fluidTop) {
                normalized.add(LodColumn.pack(fluidState, cursor, fluidTop,
                        LodColumn.FLAG_SKY_OPEN, coverage));
            }
        }
        normalized.sort(java.util.Comparator.comparingInt(LodColumn::minY));
        return LodColumnReducer.limitIntervals(mergeAdjacent(normalized));
    }

    private static LodColumn[] reduceLevel(LodColumn[] children, int level) {
        return reduceLevel(children, level, true);
    }

    private static LodColumn[] reduceLevel(LodColumn[] children, int level,
                                           boolean anchorLandmarks) {
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
                    LodColumn reduced = LodColumnReducer.reduce(group, 1 << (level << 1));
                    parents[z * side + x] = anchorLandmarks ? anchorSupports(reduced) : reduced;
                }
            }
        }
        return parents;
    }

    private static LodColumn merge(LodColumn natural, LodColumn feature) {
        if (feature.size() == 0) return natural;
        ArrayList<Long> intervals = new ArrayList<>(natural.size() + feature.size());
        for (int i = 0; i < natural.size(); i++) intervals.add(natural.interval(i));
        long terrain = outerTerrainInterval(natural);
        int terrainTop = terrain == 0 ? 0 : LodColumn.maxY(terrain);
        for (int i = 0; i < feature.size(); i++) {
            long interval = feature.interval(i);
            int minY = Math.max(terrainTop, LodColumn.minY(interval));
            if (minY < LodColumn.maxY(interval)) {
                intervals.add(LodColumn.pack(LodColumn.state(interval), minY,
                        LodColumn.maxY(interval), LodColumn.flags(interval), LodColumn.coverage(interval)));
            }
        }
        intervals.sort(java.util.Comparator.comparingInt(LodColumn::minY));
        return anchorSupports(LodColumnReducer.limitIntervals(intervals));
    }

    /** Hoechste feste, nicht als Landmarke getrennte Aussenflaeche einer Spalte. */
    static long outerTerrainInterval(LodColumn column) {
        long best = 0;
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            int state = LodColumn.state(interval);
            if (Blocks.getState(state).isFluid() || LodColumn.landmark(interval)) continue;
            if (best == 0 || LodColumn.maxY(interval) > LodColumn.maxY(best)) best = interval;
        }
        return best;
    }

    /** Bindet nur explizit geerdete Landmark-Intervalle an die reduzierte Aussenflaeche. */
    private static LodColumn anchorSupports(LodColumn column) {
        long terrain = outerTerrainInterval(column);
        if (terrain == 0) return column;
        int terrainTop = LodColumn.maxY(terrain);
        boolean changed = false;
        ArrayList<Long> intervals = new ArrayList<>(column.size());
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (!LodColumn.support(interval)) {
                intervals.add(interval);
                continue;
            }
            changed |= LodColumn.minY(interval) != terrainTop;
            if (terrainTop >= LodColumn.maxY(interval)) continue;
            intervals.add(LodColumn.pack(LodColumn.state(interval), terrainTop,
                    LodColumn.maxY(interval), LodColumn.flags(interval),
                    LodColumn.coverage(interval)));
        }
        if (!changed) return column;
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
                            LodColumn.minY(previous), LodColumn.maxY(interval),
                            LodColumn.flags(previous) | LodColumn.flags(interval),
                            Math.max(LodColumn.coverage(previous), LodColumn.coverage(interval))));
                    continue;
                }
            }
            merged.add(interval);
        }
        return merged;
    }

    private static ArrayList<Long> scanRuns(Chunk chunk, int x, int z) {
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
        return runs;
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
