package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.LodFeatureBuffer;
import de.skyengine.game.world.save.ChunkSerializer;
import de.skyengine.game.world.save.WorldStorage;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Einheitliche, bedarfsgesteuerte LOD-Quelle fuer Live-, Speicher- und Generator-Daten. */
public final class PersistentLodDataSource implements LodDataSource, AutoCloseable {

    private record ExactChunk(Chunk chunk, boolean resident) {}

    private record GeneratedResult(ChunkLodColumns columns, long featureNanos,
                                   long terrainNanos, long projectionNanos, long reductionNanos) {}

    private static final class PhaseStats {
        private final long[] recent = new long[256];
        private long count, total, max;
        private int cursor;

        synchronized void add(long nanos) {
            if (nanos <= 0) return;
            this.count++;
            this.total += nanos;
            this.max = Math.max(this.max, nanos);
            this.recent[this.cursor++ & (this.recent.length - 1)] = nanos;
        }

        synchronized String text() {
            if (this.count == 0) return "-";
            int length = (int) Math.min(this.count, this.recent.length);
            long[] sorted = java.util.Arrays.copyOf(this.recent, length);
            java.util.Arrays.sort(sorted);
            long p95 = sorted[Math.max(0, (int) Math.ceil(length * 0.95) - 1)];
            return String.format(java.util.Locale.ROOT, "%.2f/p95 %.2f/max %.2f ms",
                    this.total / 1_000_000.0 / this.count, p95 / 1_000_000.0, this.max / 1_000_000.0);
        }
    }

    private static final long MIB = 1024L * 1024L;
    private static final long MAX_CACHE_BYTES = adaptiveCacheBytes(Runtime.getRuntime().maxMemory());

    private final Logger logger = LogManager.getLogger(PersistentLodDataSource.class.getName());
    private final ChunkManager chunks;
    private final WorldStorage storage;
    private final WorldGenerator generator;
    private final ChunkDecorator decorator;
    private final boolean imported;
    private final LodCacheStore disk;
    private final Map<Long, ChunkLodColumns> cache = new LinkedHashMap<>(256, 0.75F, true);
    /* Separat gespeichert, weil ChunkLodColumns durch Lazy-Ableitung/merge nachtraeglich waechst. */
    private final Map<Long, Long> cacheSizes = new java.util.HashMap<>();
    private final Map<Long, CompletableFuture<ChunkLodColumns>> builds = new ConcurrentHashMap<>();
    private final Set<Long> invalidated = ConcurrentHashMap.newKeySet();
    private final AtomicLong memoryHits = new AtomicLong();
    private final AtomicLong diskHits = new AtomicLong();
    private final AtomicLong generatedBuilds = new AtomicLong();
    private final AtomicLong bulkWindows = new AtomicLong();
    private final AtomicLong bulkChunkLookups = new AtomicLong();
    private final PhaseStats totalTimes = new PhaseStats();
    private final PhaseStats diskTimes = new PhaseStats();
    private final PhaseStats storageTimes = new PhaseStats();
    private final PhaseStats exactTimes = new PhaseStats();
    private final PhaseStats terrainTimes = new PhaseStats();
    private final PhaseStats featureTimes = new PhaseStats();
    private final PhaseStats projectionTimes = new PhaseStats();
    private final PhaseStats reductionTimes = new PhaseStats();
    private long cacheBytes;

    static long adaptiveCacheBytes(long maxHeapBytes) {
        return Math.clamp(maxHeapBytes / 4, 64L * MIB, 512L * MIB);
    }

    public PersistentLodDataSource(ChunkManager chunks, WorldStorage storage,
                                   WorldGenerator generator, ChunkDecorator decorator,
                                   boolean imported, File directory, int generatorVersion) {
        this.chunks = chunks;
        this.storage = storage;
        this.generator = generator;
        this.decorator = decorator;
        this.imported = imported;
        this.disk = new LodCacheStore(directory, generatorVersion, decorator.cacheFingerprint());
        storage.setWriteListener((cx, cz) -> {
            long key = Chunk.key(cx, cz);
            synchronized (this.cache) {
                ChunkLodColumns removed = this.cache.remove(key);
                Long bytes = this.cacheSizes.remove(key);
                if (removed != null && bytes != null) this.cacheBytes -= bytes;
            }
            this.invalidated.add(key);
        });
    }

    @Override public boolean hasColumns() { return true; }
    @Override public boolean hasWorldBottom() {
        return !this.imported && this.generator.lodWorldBottomState() != Blocks.AIR;
    }

    @Override
    public ExactColumnSampler openExactColumnSampler() {
        /* Der Cache lebt nur fuer einen Meshing-Job. Dadurch wird ein Save-/Generator-Chunk an
           einer langen Naht genau einmal materialisiert, kann aber nie ueber eine spaetere
           Weltmutation hinweg veralten. */
        return new ExactColumnSampler() {
            private final Map<Long, ExactChunk> chunks = new HashMap<>();

            @Override
            public boolean sampleColumn(int x, int z, int[] target) {
                if (target.length < Chunk.HEIGHT) {
                    throw new IllegalArgumentException("Exakte LOD-Spalte ist zu klein: " + target.length);
                }
                int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
                ExactChunk exact = this.chunks.computeIfAbsent(Chunk.key(cx, cz),
                        ignored -> loadExactChunk(cx, cz));
                Chunk chunk = exact.chunk;
                chunk.readLock().lock();
                try {
                    int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
                    for (int y = 0; y < Chunk.HEIGHT; y++) target[y] = chunk.getBlock(lx, y, lz);
                } finally {
                    chunk.readLock().unlock();
                }
                return exact.resident;
            }

            @Override
            public boolean sampleRenderedBoundaryFaces(int x, int z, int face, int[] target) {
                if (target.length < Chunk.SECTIONS) {
                    throw new IllegalArgumentException("L0-Randmaske ist zu klein: " + target.length);
                }
                java.util.Arrays.fill(target, 0);
                int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
                ExactChunk exact = this.chunks.computeIfAbsent(Chunk.key(cx, cz),
                        ignored -> loadExactChunk(cx, cz));
                Chunk chunk = exact.chunk;
                if (!exact.resident || !chunk.isFullyUploaded()) return false;

                int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
                int tangent;
                switch (face) {
                    case 2 -> {
                        if (lz != 0) return false;
                        tangent = lx;
                    }
                    case 3 -> {
                        if (lz != ChunkSection.MASK) return false;
                        tangent = lx;
                    }
                    case 4 -> {
                        if (lx != 0) return false;
                        tangent = lz;
                    }
                    case 5 -> {
                        if (lx != ChunkSection.MASK) return false;
                        tangent = lz;
                    }
                    default -> throw new IllegalArgumentException(
                            "Keine horizontale L0-Seite: " + face);
                }
                for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                    target[sectionY] = chunk.boundaryFaceBits(sectionY, face, tangent);
                }
                return true;
            }

            @Override
            public void close() {
                this.chunks.clear();
            }
        };
    }

    private ExactChunk loadExactChunk(int cx, int cz) {
        Chunk live = this.chunks.getChunk(cx, cz);
        if (live != null && live.status.isAtLeast(ChunkStatus.DECORATED)) {
            return new ExactChunk(live, true);
        }

        Chunk snapshot = new Chunk(cx, cz);
        byte[] payload = this.storage.readChunk(cx, cz);
        if (payload != null) {
            try {
                ChunkSerializer.deserialize(snapshot, payload, null);
                return new ExactChunk(snapshot, false);
            } catch (Exception e) {
                this.logger.warning("Exakte LOD-Spalte fuer Chunk (" + cx + ", " + cz
                        + ") nicht aus dem Savegame lesbar", e);
            }
        }
        if (!this.imported) {
            this.generator.generate(snapshot);
            this.decorator.decorateForLod(snapshot);
        }
        return new ExactChunk(snapshot, false);
    }

    @Override
    public LodColumn sampleColumn(int x, int z, int size) {
        int level = level(size);
        int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
        return this.columns(cx, cz, level).get(x & ChunkSection.MASK, z & ChunkSection.MASK, size);
    }

    @Override
    public void sampleColumns(int startX, int startZ, int size, int width, int height,
                              LodColumn[] target, int targetOffset, int targetStride) {
        int level = level(size);
        int endX = startX + (width - 1) * size;
        int endZ = startZ + (height - 1) * size;
        int minCx = startX >> ChunkSection.SHIFT;
        int minCz = startZ >> ChunkSection.SHIFT;
        int maxCx = endX >> ChunkSection.SHIFT;
        int maxCz = endZ >> ChunkSection.SHIFT;
        int chunksWide = maxCx - minCx + 1;
        this.bulkWindows.incrementAndGet();
        this.bulkChunkLookups.addAndGet((long) chunksWide * (maxCz - minCz + 1));
        ChunkDecorator.LodRegionFeatures features = this.decorator.lodRegion(
                minCx, minCz, chunksWide, maxCz - minCz + 1);
        ChunkLodColumns[] window = new ChunkLodColumns[chunksWide * (maxCz - minCz + 1)];
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                window[(cz - minCz) * chunksWide + cx - minCx] = this.columns(cx, cz, level, features);
            }
        }
        for (int z = 0; z < height; z++) {
            int wz = startZ + z * size;
            int cz = wz >> ChunkSection.SHIFT;
            int row = targetOffset + z * targetStride;
            for (int x = 0; x < width; x++) {
                int wx = startX + x * size;
                int cx = wx >> ChunkSection.SHIFT;
                ChunkLodColumns columns = window[(cz - minCz) * chunksWide + cx - minCx];
                target[row + x] = columns.get(wx & ChunkSection.MASK, wz & ChunkSection.MASK, size);
            }
        }
    }

    @Override
    public long sampleSurface(int x, int z, int size) {
        LodColumn column = this.sampleColumn(x, z, size);
        if (column.size() == 0) return LodDataSource.pack(Blocks.AIR, 0);
        long top = column.interval(column.size() - 1);
        return LodDataSource.pack(LodColumn.state(top), LodColumn.maxY(top) - 1);
    }

    @Override
    public long sampleGround(int x, int z, int size) {
        LodColumn column = this.sampleColumn(x, z, size);
        for (int i = column.size() - 1; i >= 0; i--) {
            long interval = column.interval(i);
            if (!Blocks.getState(LodColumn.state(interval)).isFluid()) {
                return LodDataSource.pack(LodColumn.state(interval), LodColumn.maxY(interval) - 1);
            }
        }
        return LodDataSource.pack(Blocks.AIR, 0);
    }

    @Override public int grassTintAt(int x, int z) { return this.generator.grassTintAt(x, z); }
    @Override public int foliageTintAt(int x, int z) { return this.generator.foliageTintAt(x, z); }

    public String debugStats() {
        synchronized (this.cache) {
            return "LOD-Cache: RAM-Hits=" + this.memoryHits.get() + ", Disk-Hits=" + this.diskHits.get()
                    + ", Generator-Builds=" + this.generatedBuilds.get()
                    + ", Bulk=" + this.bulkWindows.get() + "/" + this.bulkChunkLookups.get()
                    + " Fenster/Chunk-Lookups"
                    + ", Gesamt=" + this.totalTimes.text() + ", Disk=" + this.diskTimes.text()
                    + ", Storage=" + this.storageTimes.text() + ", Exakt=" + this.exactTimes.text()
                    + ", Terrain=" + this.terrainTimes.text() + ", Features=" + this.featureTimes.text()
                    + ", Projektion=" + this.projectionTimes.text() + ", Reduktion=" + this.reductionTimes.text()
                    + ", RAM=" + (this.cacheBytes >> 20) + " MiB, verworfene Writes=" + this.disk.droppedWrites();
        }
    }

    private ChunkLodColumns columns(int cx, int cz, int level) {
        return this.columns(cx, cz, level, null);
    }

    private ChunkLodColumns columns(int cx, int cz, int level,
                                    ChunkDecorator.LodRegionFeatures regionFeatures) {
        PerformanceProfiler profiler = PerformanceProfiler.get();
        PerformanceProfiler.AsyncToken sourceStarted = profiler.beginAsync();
        long chunkKey = Chunk.key(cx, cz);
        ChunkLodColumns cached = cached(chunkKey);
        if (cached != null && this.materializeCachedLevel(chunkKey, cached, level)) {
            this.memoryHits.incrementAndGet();
            profiler.recordElapsed(PerformanceProfiler.WorkerSection.LOD_SOURCE_CACHE_DISK_SAVE,
                    level, sourceStarted);
            return cached;
        }

        CompletableFuture<ChunkLodColumns> own = new CompletableFuture<>();
        CompletableFuture<ChunkLodColumns> active = this.builds.putIfAbsent(chunkKey, own);
        if (active != null) {
            ChunkLodColumns result = active.join();
            if (this.materializeCachedLevel(chunkKey, result, level)) {
                profiler.recordElapsed(PerformanceProfiler.WorkerSection.LOD_SOURCE_CACHE_DISK_SAVE,
                        level, sourceStarted);
                return result;
            }
            this.builds.remove(chunkKey, active);
            return this.columns(cx, cz, level, regionFeatures);
        }
        try {
            ChunkLodColumns result = this.build(cx, cz, chunkKey, level, cached, regionFeatures,
                    sourceStarted);
            own.complete(result);
            return result;
        } catch (Throwable error) {
            own.completeExceptionally(error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new CompletionException(error);
        } finally {
            this.builds.remove(chunkKey, own);
        }
    }

    private ChunkLodColumns build(int cx, int cz, long key, int level, ChunkLodColumns existing,
                                  ChunkDecorator.LodRegionFeatures regionFeatures,
                                  PerformanceProfiler.AsyncToken profileToken) {
        long started = System.nanoTime();
        long diskNanos = 0, storageNanos = 0, exactNanos = 0;
        long terrainNanos = 0, featureNanos = 0, projectionNanos = 0, reductionNanos = 0;
        ChunkLodColumns result = existing;
        if (result == null && !this.invalidated.contains(key)) {
            long phaseStarted = System.nanoTime();
            result = this.disk.read(cx, cz);
            diskNanos = System.nanoTime() - phaseStarted;
            if (result != null) this.diskHits.incrementAndGet();
        }
        if (result != null && result.materializeLevel(level)) {
            result = install(key, result);
            this.recordTimes(level, profileToken, System.nanoTime() - started,
                    diskNanos, 0, 0, 0, 0, 0, 0);
            return result;
        }

        ChunkLodColumns built;
        Chunk live = this.chunks.getChunk(cx, cz);
        if (live != null && live.status.isAtLeast(ChunkStatus.DECORATED)) {
            long phaseStarted = System.nanoTime();
            built = ChunkLodColumns.fromChunk(live, this.imported ? null : this.generator, level);
            exactNanos = System.nanoTime() - phaseStarted;
        } else {
            Chunk snapshot = new Chunk(cx, cz);
            long phaseStarted = System.nanoTime();
            byte[] payload = this.storage.readChunk(cx, cz);
            if (payload != null) {
                try {
                    ChunkSerializer.deserialize(snapshot, payload, null);
                    storageNanos = System.nanoTime() - phaseStarted;
                    phaseStarted = System.nanoTime();
                    built = ChunkLodColumns.fromChunk(snapshot, this.imported ? null : this.generator, level);
                    exactNanos = System.nanoTime() - phaseStarted;
                } catch (Exception e) {
                    if (storageNanos == 0) storageNanos = System.nanoTime() - phaseStarted;
                    this.logger.warning("LOD-Snapshot fuer Chunk (" + cx + ", " + cz + ") nicht lesbar", e);
                    GeneratedResult generated = generated(cx, cz, level, regionFeatures);
                    built = generated.columns;
                    featureNanos = generated.featureNanos;
                    terrainNanos = generated.terrainNanos;
                    projectionNanos = generated.projectionNanos;
                    reductionNanos = generated.reductionNanos;
                }
            } else if (!this.imported) {
                storageNanos = System.nanoTime() - phaseStarted;
                GeneratedResult generated = generated(cx, cz, level, regionFeatures);
                built = generated.columns;
                featureNanos = generated.featureNanos;
                terrainNanos = generated.terrainNanos;
                projectionNanos = generated.projectionNanos;
                reductionNanos = generated.reductionNanos;
            } else {
                storageNanos = System.nanoTime() - phaseStarted;
                phaseStarted = System.nanoTime();
                built = ChunkLodColumns.fromChunk(snapshot, null, level);
                exactNanos = System.nanoTime() - phaseStarted;
            }
        }

        if (result == null) result = built;
        else result.merge(built);
        result = install(key, result);
        this.disk.writeLater(cx, cz, result);
        this.invalidated.remove(key);
        this.recordTimes(level, profileToken, System.nanoTime() - started,
                diskNanos, storageNanos, exactNanos,
                terrainNanos, featureNanos, projectionNanos, reductionNanos);
        return result;
    }

    private GeneratedResult generated(int cx, int cz, int level,
                                      ChunkDecorator.LodRegionFeatures regionFeatures) {
        this.generatedBuilds.incrementAndGet();
        long featureStarted = System.nanoTime();
        LodFeatureBuffer features = regionFeatures == null
                ? this.decorator.decorateForLod(cx, cz) : regionFeatures.forChunk(cx, cz);
        long featureNanos = System.nanoTime() - featureStarted;
        ChunkLodColumns.GeneratedBuild generated = ChunkLodColumns.buildFromGenerator(
                this.generator, features, cx, cz, level);
        return new GeneratedResult(generated.columns(), featureNanos, generated.terrainNanos(),
                generated.projectionNanos(), generated.reductionNanos());
    }

    private void recordTimes(int level, PerformanceProfiler.AsyncToken profileToken,
                             long total, long disk, long storage, long exact, long terrain,
                             long feature, long projection, long reduction) {
        this.totalTimes.add(total);
        this.diskTimes.add(disk);
        this.storageTimes.add(storage);
        this.exactTimes.add(exact);
        this.terrainTimes.add(terrain);
        this.featureTimes.add(feature);
        this.projectionTimes.add(projection);
        this.reductionTimes.add(reduction);
        PerformanceProfiler profiler = PerformanceProfiler.get();
        recordOptional(profiler, PerformanceProfiler.WorkerSection.LOD_SOURCE_CACHE_DISK_SAVE,
                level, disk + storage + exact, profileToken);
        recordOptional(profiler, PerformanceProfiler.WorkerSection.LOD_SOURCE_TERRAIN,
                level, terrain, profileToken);
        recordOptional(profiler, PerformanceProfiler.WorkerSection.LOD_SOURCE_FEATURES,
                level, feature, profileToken);
        recordOptional(profiler, PerformanceProfiler.WorkerSection.LOD_PROJECTION,
                level, projection, profileToken);
        recordOptional(profiler, PerformanceProfiler.WorkerSection.LOD_REDUCTION,
                level, reduction, profileToken);
    }

    private static void recordOptional(PerformanceProfiler profiler,
                                       PerformanceProfiler.WorkerSection section,
                                       int level, long nanos,
                                       PerformanceProfiler.AsyncToken token) {
        if (nanos > 0) profiler.record(section, level, nanos, token);
    }

    private ChunkLodColumns cached(long key) {
        synchronized (this.cache) { return this.cache.get(key); }
    }

    private boolean materializeCachedLevel(long key, ChunkLodColumns value, int level) {
        synchronized (this.cache) {
            if (!value.materializeLevel(level)) return false;
            if (this.cache.get(key) == value) {
                long previous = this.cacheSizes.getOrDefault(key, value.estimatedBytes());
                long current = value.estimatedBytes();
                this.cacheSizes.put(key, current);
                this.cacheBytes += current - previous;
            }
            return true;
        }
    }

    private ChunkLodColumns install(long key, ChunkLodColumns value) {
        synchronized (this.cache) {
            ChunkLodColumns previous = this.cache.get(key);
            if (previous != null && previous != value) {
                this.cacheBytes -= this.cacheSizes.getOrDefault(key, previous.estimatedBytes());
                previous.merge(value);
                value = previous;
            } else if (previous != null) {
                this.cacheBytes -= this.cacheSizes.getOrDefault(key, previous.estimatedBytes());
            }
            this.cache.put(key, value);
            long bytes = value.estimatedBytes();
            this.cacheSizes.put(key, bytes);
            this.cacheBytes += bytes;
            var iterator = this.cache.entrySet().iterator();
            while (this.cacheBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                Map.Entry<Long, ChunkLodColumns> entry = iterator.next();
                iterator.remove();
                this.cacheBytes -= this.cacheSizes.remove(entry.getKey());
            }
            return value;
        }
    }

    private static int level(int size) {
        if (size <= 0 || size > ChunkSection.SIZE || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("Ungueltige LOD-Zellgroesse: " + size);
        }
        return Integer.numberOfTrailingZeros(size);
    }

    @Override
    public void close() {
        synchronized (this.cache) {
            this.cache.clear();
            this.cacheSizes.clear();
            this.cacheBytes = 0;
        }
        this.decorator.clearLodFeatureCache();
        this.disk.close();
    }
}
