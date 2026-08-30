package de.skyengine.game.world.lod;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.SurfaceSample;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.save.WorldStorage;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;

/** Persistente Quelle der volumetrischen 32^3-Hierarchie. */
public final class PersistentLodDataSource implements AutoCloseable {

    private record ColumnKey(int x, int z, int level) {}

    /** Vollstaendig geladener und fuer einen Mesh-Build unveraenderlicher Center-/Halo-Stand. */
    public record VolumeNeighborhood(LodVolumeHierarchy.Key key, LodVoxelSection center,
                                     LodVoxelSection west, LodVoxelSection east,
                                     LodVoxelSection north, LodVoxelSection south,
                                     LodVoxelSection below, LodVoxelSection above,
                                     long[] westHalo, long[] eastHalo,
                                     long[] northHalo, long[] southHalo,
                                     long revision) {
        long sample(int x, int y, int z) {
            int dx = Math.floorDiv(x, LodVoxelSection.SIZE);
            int dy = Math.floorDiv(y, LodVoxelSection.SIZE);
            int dz = Math.floorDiv(z, LodVoxelSection.SIZE);
            LodVoxelSection neighbor = dx < 0 ? this.west : dx > 0 ? this.east
                    : dz < 0 ? this.north : dz > 0 ? this.south
                    : dy < 0 ? this.below : dy > 0 ? this.above : null;
            if (neighbor != null) return neighbor.get(Math.floorMod(x, LodVoxelSection.SIZE),
                    Math.floorMod(y, LodVoxelSection.SIZE), Math.floorMod(z, LodVoxelSection.SIZE));
            int tangent = dx != 0 ? Math.floorMod(z, LodVoxelSection.SIZE)
                    : Math.floorMod(x, LodVoxelSection.SIZE);
            int haloIndex = Math.floorMod(y, LodVoxelSection.SIZE) * LodVoxelSection.SIZE + tangent;
            if (dx < 0 && this.westHalo != null) return this.westHalo[haloIndex];
            if (dx > 0 && this.eastHalo != null) return this.eastHalo[haloIndex];
            if (dz < 0 && this.northHalo != null) return this.northHalo[haloIndex];
            if (dz > 0 && this.southHalo != null) return this.southHalo[haloIndex];
            return 0L;
        }
    }

    private final Logger logger = LogManager.getLogger(PersistentLodDataSource.class.getName());
    private final ChunkManager chunks;
    private final WorldGenerator generator;
    private final ChunkDecorator decorator;
    private final boolean generatorBacked;
    private final LodVolumeHierarchy volumes;
    private final LodVolumeStore disk;
    private final Set<Long> publishedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ColumnKey> knownMissingColumns = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ColumnKey, CompletableFuture<Void>> columnLoads =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Chunk> pendingSnapshots = new ConcurrentLinkedQueue<>();
    /* Pro Column existiert hoechstens ein Dirty-Snapshot. Die Queue enthaelt nur Keys; wird
       waehrend eines Writes erneut publiziert, legt compute() genau einen Folgeeintrag an.
       Anders als die alte 64-Section-Queue verliert dieser Pfad niemals Teilstacks. */
    private final ConcurrentHashMap<ColumnKey, LodVolumeStore.Column> dirtyColumns =
            new ConcurrentHashMap<>();
    private final Set<ColumnKey> queuedWrites = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ColumnKey> pendingWrites = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean writeDrainScheduled = new AtomicBoolean();
    private final ExecutorService cacheWriter = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "LOD Cache Writer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong generatedColumnCount = new AtomicLong();
    private final AtomicBoolean snapshotInflight = new AtomicBoolean();
    private final int maxActiveWork = Math.clamp(Math.min(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4),
            Math.max(2, (int) (Runtime.getRuntime().maxMemory() / (512L << 20)))), 2, 4);
    private final Semaphore workSlots = new Semaphore(this.maxActiveWork);
    private final Semaphore rootBuildSlot = new Semaphore(1);
    private final AtomicInteger revision = new AtomicInteger();
    private volatile boolean failed;

    public PersistentLodDataSource(ChunkManager chunks, WorldStorage storage,
                                   WorldGenerator generator, ChunkDecorator decorator,
                                   boolean imported, File directory, int generatorVersion) {
        this.chunks = chunks;
        this.generator = generator;
        this.decorator = decorator;
        this.generatorBacked = !imported;
        int fingerprint = 31 * (31 * generatorVersion + decorator.cacheFingerprint())
                + LodBlockRules.volumeFingerprint();
        this.volumes = new LodVolumeHierarchy(generator::fillLodVolume);
        this.disk = new LodVolumeStore(directory, fingerprint);
        storage.setWriteListener((cx, cz) -> {
            long chunkKey = Chunk.key(cx, cz);
            this.publishedChunks.remove(chunkKey);
            this.volumes.invalidateColumn(cx, cz);
            for (int level = 0; level <= LodVoxelSection.MAX_LEVEL; level++) {
                this.knownMissingColumns.remove(new ColumnKey(Math.floorDiv(cx, 1 << level),
                        Math.floorDiv(cz, 1 << level), level));
            }
            this.revision.incrementAndGet();
            Chunk live = this.chunks.getChunk(cx, cz);
            if (live != null && live.status == ChunkStatus.READY) this.queueLiveVolumes(live);
        });
    }

    public LodVoxelSection volume(int nodeX, int nodeY, int nodeZ, int level) {
        if (this.failed) return null;
        if (nodeY < 0 || nodeY >= LodVolumeStore.verticalNodes(level)) return null;
        LodVolumeHierarchy.Key key = new LodVolumeHierarchy.Key(nodeX, nodeY, nodeZ, level);
        LodVoxelSection resident = this.volumes.get(key);
        if (resident != null) return resident;
        this.loadColumn(nodeX, nodeZ, level, this.generatorBacked);
        return this.volumes.get(key);
    }

    /** Reiner Residency-Lookup fuer Render-/Effektabfragen; fuehrt niemals Disk-IO oder
        Generatorarbeit auf dem aufrufenden Thread aus. */
    public LodVoxelSection residentVolume(LodVolumeHierarchy.Key key) {
        return this.volumes.get(key);
    }

    private void loadColumn(int nodeX, int nodeZ, int level, boolean allowGenerate) {
        ColumnKey column = new ColumnKey(nodeX, nodeZ, level);
        if (!allowGenerate && this.knownMissingColumns.contains(column)) return;
        CompletableFuture<Void> created = new CompletableFuture<>();
        CompletableFuture<Void> existing = this.columnLoads.putIfAbsent(column, created);
        if (existing != null) {
            try { existing.join(); }
            catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                throw exception;
            }
            /* Ein reiner Halo-Read kann dieselbe Column gerade als Disk-Miss pruefen. Wird
               sie gleichzeitig als sichtbares Center benoetigt, muss der wartende Aufrufer
               danach trotzdem generieren; sonst wuerde null als dauerhaft unavailable gelten. */
            if (allowGenerate && this.volumes.get(new LodVolumeHierarchy.Key(
                    nodeX, 0, nodeZ, level)) == null) {
                this.columnLoads.remove(column, existing);
                this.loadColumn(nodeX, nodeZ, level, true);
            }
            return;
        }
        try {
            /* Ein noch nicht geflushter Write-Back-Snapshot ist bereits ein vollwertiger
               Cachetreffer. Bei Speicherdruck darf er nicht parallel neu generiert werden. */
            LodVolumeStore.Column stored = this.dirtyColumns.get(column);
            if (stored == null && !this.knownMissingColumns.contains(column)) {
                stored = this.disk.readColumn(nodeX, nodeZ, level);
            }
            if (stored != null) {
                this.cacheHits.incrementAndGet();
                this.knownMissingColumns.remove(column);
                this.publishColumn(stored, true);
            } else {
                if (this.knownMissingColumns.add(column)) this.cacheMisses.incrementAndGet();
                if (allowGenerate) {
                    this.generateColumnNow(nodeX, nodeZ, level);
                    this.knownMissingColumns.remove(column);
                }
            }
            created.complete(null);
        } catch (Throwable error) {
            created.completeExceptionally(error);
            throw error;
        } finally {
            this.columnLoads.remove(column, created);
        }
    }

    /** Ein Terrain-/Feature-Sample pro X/Z-Zelle, verteilt auf den ganzen Welt-Y-Stack. */
    private void generateColumnNow(int nodeX, int nodeZ, int level) {
        int cell = 1 << level;
        int verticalNodes = LodVolumeStore.verticalNodes(level);
        LodVoxelSection[] sections = new LodVoxelSection[verticalNodes];
        long[] ground = new long[LodVoxelSection.SIZE * LodVoxelSection.SIZE];
        long[] surface = new long[ground.length];
        if (level == 0) {
            this.generator.fillLodSurfaces(nodeX, nodeZ, ground, surface);
        } else {
            int originX = nodeX * LodVoxelSection.SIZE * cell;
            int originZ = nodeZ * LodVoxelSection.SIZE * cell;
            for (int z = 0; z < LodVoxelSection.SIZE; z++) for (int x = 0; x < LodVoxelSection.SIZE; x++) {
                WorldGenerator.LodSurfaces sampled = this.generator.sampleLodSurfaces(
                        originX + x * cell + cell / 2, originZ + z * cell + cell / 2);
                int index = z * LodVoxelSection.SIZE + x;
                ground[index] = sampled.ground();
                surface[index] = sampled.surface();
            }
        }
        int bottomState = this.generator.lodWorldBottomState();
        int cellsY = Chunk.HEIGHT / cell;
        for (int z = 0; z < LodVoxelSection.SIZE; z++) for (int x = 0; x < LodVoxelSection.SIZE; x++) {
            int index = z * LodVoxelSection.SIZE + x;
            int groundState = SurfaceSample.block(ground[index]);
            int groundTop = SurfaceSample.height(ground[index]) + 1;
            int surfaceState = SurfaceSample.block(surface[index]);
            int surfaceTop = Math.max(groundTop, SurfaceSample.height(surface[index]) + 1);
            int topCell = Math.min(cellsY, Math.max(0, (surfaceTop + cell - 1) / cell));
            for (int cellY = 0; cellY < topCell; cellY++) {
                int minY = cellY * cell, maxY = minY + cell;
                int filled = Math.max(0, Math.min(maxY, surfaceTop) - minY);
                if (filled == 0) continue;
                int state;
                int importance;
                if (minY < 1 && bottomState != Blocks.AIR) {
                    state = bottomState;
                    importance = 4;
                } else if (maxY > groundTop && surfaceState != Blocks.AIR) {
                    state = surfaceState;
                    importance = 12;
                } else {
                    state = groundState;
                    importance = 4;
                }
                if (state == Blocks.AIR) continue;
                int coverage = Math.clamp((filled * 255 + cell / 2) / cell, 1, 255);
                int sky = maxY >= surfaceTop ? 15 : 0;
                int sectionY = cellY >>> 5;
                LodVoxelSection section = sections[sectionY];
                if (section == null) {
                    section = sections[sectionY] = new LodVoxelSection(nodeX, sectionY, nodeZ, level,
                            LodVoxelSection.Completeness.PROVISIONAL);
                }
                section.set(x, cellY & 31, z, LodVoxel.pack(state, sky,
                        0, 0, 0, coverage, LodVoxel.PROVENANCE_ANALYTIC, importance));
            }
        }
        if (level <= 1) this.applyGeneratedFeatures(nodeX, nodeZ, level, sections);
        for (int nodeY = 0; nodeY < sections.length; nodeY++) {
            LodVoxelSection section = sections[nodeY];
            if (section == null) {
                section = sections[nodeY] = LodVoxelSection.empty(nodeX, nodeY, nodeZ, level,
                        LodVoxelSection.Completeness.PROVISIONAL);
            }
            section.compact();
        }
        LodVolumeStore.Column column = new LodVolumeStore.Column(nodeX, nodeZ, level, sections);
        this.publishColumn(column, false);
        this.generatedColumnCount.incrementAndGet();
        this.queueWrite(column);
    }

    private void publishColumn(LodVolumeStore.Column column, boolean restoring) {
        for (LodVoxelSection section : column.sections()) {
            if (restoring) this.volumes.restore(section);
            else this.volumes.publish(section);
        }
    }

    private LodVolumeStore.Column residentColumn(ColumnKey key, boolean canonicalOnly) {
        LodVoxelSection[] sections = new LodVoxelSection[LodVolumeStore.verticalNodes(key.level)];
        for (int y = 0; y < sections.length; y++) {
            LodVoxelSection section = this.volumes.get(new LodVolumeHierarchy.Key(
                    key.x, y, key.z, key.level));
            if (section == null || canonicalOnly
                    && section.completeness() != LodVoxelSection.Completeness.CANONICAL) return null;
            sections[y] = section;
        }
        return new LodVolumeStore.Column(key.x, key.z, key.level, sections);
    }

    private void applyGeneratedFeatures(int nodeX, int nodeZ, int level,
                                        LodVoxelSection[] sections) {
        int chunks = 1 << level;
        int baseChunkX = nodeX * chunks, baseChunkZ = nodeZ * chunks;
        int cell = 1 << level;
        int originX = nodeX * LodVoxelSection.SIZE * cell;
        int originZ = nodeZ * LodVoxelSection.SIZE * cell;
        int singleBlockCoverage = Math.max(1, 255 / (cell * cell * cell));
        ChunkDecorator.LodRegionFeatures features = this.decorator.lodRegion(
                baseChunkX, baseChunkZ, chunks, chunks);
        for (int cz = 0; cz < chunks; cz++) for (int cx = 0; cx < chunks; cx++) {
            int chunkX = baseChunkX + cx, chunkZ = baseChunkZ + cz;
            features.forChunk(chunkX, chunkZ).forEach((localX, y, localZ, state) -> {
                int simplified = LodBlockRules.simplifyVolume(state);
                if (simplified == Blocks.AIR) return;
                int vx = Math.floorDiv((chunkX << ChunkSection.SHIFT) + localX - originX, cell);
                int vz = Math.floorDiv((chunkZ << ChunkSection.SHIFT) + localZ - originZ, cell);
                int cellY = y / cell;
                if ((vx | vz | cellY) < 0 || vx >= LodVoxelSection.SIZE
                        || vz >= LodVoxelSection.SIZE || cellY >= sections.length * 32) return;
                int sectionY = cellY >>> 5;
                LodVoxelSection section = sections[sectionY];
                if (section == null) {
                    section = sections[sectionY] = new LodVoxelSection(nodeX, sectionY, nodeZ, level,
                            LodVoxelSection.Completeness.PROVISIONAL);
                }
                section.set(vx, cellY & 31, vz, LodVoxel.pack(simplified,
                        15, 0, 0, 0, singleBlockCoverage, LodVoxel.PROVENANCE_GENERATED,
                        LodBlockRules.volumeImportance(simplified)));
            });
        }
    }

    /** Nachbarn duerfen beim Meshing niemals weitere analytische Vollknoten erzeugen. */
    public LodVoxelSection availableVolume(int nodeX, int nodeY, int nodeZ, int level) {
        if (this.failed) return null;
        if (nodeY < 0 || nodeY >= LodVolumeStore.verticalNodes(level)) return null;
        LodVolumeHierarchy.Key key = new LodVolumeHierarchy.Key(nodeX, nodeY, nodeZ, level);
        LodVoxelSection resident = this.volumes.get(key);
        if (resident != null) return resident;
        this.loadColumn(nodeX, nodeZ, level, false);
        return this.volumes.get(key);
    }

    /** Laedt Center und alle sechs Nachbarn vor der Revisionsaufnahme. Disk-Residency kann
        den Build danach nicht mehr als stale markieren. Fehlende horizontale Nachbarn
        erhalten weiterhin ein analytisches Ein-Zellen-Halo ohne Voll-Column-Generierung. */
    public VolumeNeighborhood neighborhood(LodVolumeHierarchy.Key key) {
        LodVoxelSection center = this.volume(key.x(), key.y(), key.z(), key.level());
        if (center == null) return null;
        int x = key.x(), y = key.y(), z = key.z(), level = key.level();
        LodVoxelSection west = this.availableVolume(x - 1, y, z, level);
        LodVoxelSection east = this.availableVolume(x + 1, y, z, level);
        LodVoxelSection north = this.availableVolume(x, y, z - 1, level);
        LodVoxelSection south = this.availableVolume(x, y, z + 1, level);
        LodVoxelSection below = this.availableVolume(x, y - 1, z, level);
        LodVoxelSection above = this.availableVolume(x, y + 1, z, level);
        long[] westHalo = west == null ? this.analyticHorizontalHalo(x, y, z, level, -1, 0) : null;
        long[] eastHalo = east == null ? this.analyticHorizontalHalo(x, y, z, level, 1, 0) : null;
        long[] northHalo = north == null ? this.analyticHorizontalHalo(x, y, z, level, 0, -1) : null;
        long[] southHalo = south == null ? this.analyticHorizontalHalo(x, y, z, level, 0, 1) : null;
        return new VolumeNeighborhood(key, center, west, east, north, south, below, above,
                westHalo, eastHalo, northHalo, southHalo, this.meshRevision(key));
    }

    /** Analytisches horizontales Ein-Zellen-Halo. Index ist y*32 + tangentiale Koordinate. */
    public long[] analyticHorizontalHalo(int nodeX, int nodeY, int nodeZ, int level,
                                         int dx, int dz) {
        if (!this.generatorBacked || Math.abs(dx) + Math.abs(dz) != 1) return null;
        LodVolumeRequest request = new LodVolumeRequest(nodeX + dx, nodeY, nodeZ + dz, level);
        long[] result = new long[LodVoxelSection.SIZE * LodVoxelSection.SIZE];
        if (dx != 0) {
            int x = dx < 0 ? LodVoxelSection.SIZE - 1 : 0;
            for (int z = 0; z < LodVoxelSection.SIZE; z++) {
                int finalZ = z;
                this.generator.fillLodVolumeColumn(request, x, z,
                        (ignoredX, y, ignoredZ, value) -> result[y * LodVoxelSection.SIZE + finalZ] = value);
            }
        } else {
            int z = dz < 0 ? LodVoxelSection.SIZE - 1 : 0;
            for (int x = 0; x < LodVoxelSection.SIZE; x++) {
                int finalX = x;
                this.generator.fillLodVolumeColumn(request, x, z,
                        (ignoredX, y, ignoredZ, value) -> result[y * LodVoxelSection.SIZE + finalX] = value);
            }
        }
        return result;
    }

    public boolean isAvailable() { return !this.failed; }
    public int revision() { return this.revision.get(); }
    boolean tryAcquireWork() { return !this.failed && this.workSlots.tryAcquire(); }
    boolean tryAcquireMeshWork(int level) {
        if (!this.tryAcquireWork()) return false;
        if (level != LodVoxelSection.MAX_LEVEL || this.rootBuildSlot.tryAcquire()) return true;
        this.workSlots.release();
        return false;
    }
    void releaseWork() { this.workSlots.release(); }
    void releaseMeshWork(int level) {
        if (level == LodVoxelSection.MAX_LEVEL) this.rootBuildSlot.release();
        this.workSlots.release();
    }
    int activeWorkCount() { return this.maxActiveWork - this.workSlots.availablePermits(); }
    int maxActiveWorkCount() { return this.maxActiveWork; }
    public int volumeNodeCount() { return this.volumes.size(); }
    public long volumeEstimatedBytes() { return this.volumes.estimatedBytes(); }
    public long cacheHitCount() { return this.cacheHits.get(); }
    public long cacheMissCount() { return this.cacheMisses.get(); }
    public long generatedColumns() { return this.generatedColumnCount.get(); }
    public int dirtyColumnCount() { return this.dirtyColumns.size(); }

    /** Revision des Zentrums und seiner sechs Meshing-Nachbarn. */
    public long meshRevision(LodVolumeHierarchy.Key key) {
        long value = mix(this.volumes.contentVersion(key));
        value ^= Long.rotateLeft(mix(this.volumes.canonicalContentVersion(new LodVolumeHierarchy.Key(
                key.x() - 1, key.y(), key.z(), key.level()))), 7);
        value ^= Long.rotateLeft(mix(this.volumes.canonicalContentVersion(new LodVolumeHierarchy.Key(
                key.x() + 1, key.y(), key.z(), key.level()))), 11);
        value ^= Long.rotateLeft(mix(this.volumes.canonicalContentVersion(new LodVolumeHierarchy.Key(
                key.x(), key.y(), key.z() - 1, key.level()))), 13);
        value ^= Long.rotateLeft(mix(this.volumes.canonicalContentVersion(new LodVolumeHierarchy.Key(
                key.x(), key.y(), key.z() + 1, key.level()))), 17);
        value ^= Long.rotateLeft(mix(this.volumes.canonicalContentVersion(new LodVolumeHierarchy.Key(
                key.x(), key.y() - 1, key.z(), key.level()))), 19);
        value ^= Long.rotateLeft(mix(this.volumes.canonicalContentVersion(new LodVolumeHierarchy.Key(
                key.x(), key.y() + 1, key.z(), key.level()))), 29);
        return value;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return value;
    }

    public void queueLiveVolumes(Chunk chunk) {
        if (!this.failed && this.publishedChunks.add(Chunk.key(chunk.chunkX, chunk.chunkZ))) {
            this.pendingSnapshots.add(chunk);
        }
    }

    /** Pro Welt maximal ein Snapshot-Job; dadurch liegen nie mehrere 16-Section-Rohkopien an. */
    public void pumpVolumeSnapshots(int playerChunkX, int playerChunkZ, int scheduleVersion) {
        if (this.failed || !this.snapshotInflight.compareAndSet(false, true)) return;
        if (!this.tryAcquireWork()) {
            this.snapshotInflight.set(false);
            return;
        }
        Chunk chunk = this.pendingSnapshots.poll();
        if (chunk == null) {
            this.snapshotInflight.set(false);
            this.releaseWork();
            return;
        }
        long chunkKey = Chunk.key(chunk.chunkX, chunk.chunkZ);
        double dx = chunk.chunkX - (double) playerChunkX;
        double dz = chunk.chunkZ - (double) playerChunkZ;
        int band = Math.max(0, (int) (Math.sqrt(dx * dx + dz * dz) / 16.0));
        /* Erst nach fertiger Root-/Guard-Abdeckung zugelassen; dann muss der eine reservierte
           Slot aber auch zeitnah laufen. Level 0 ordnet den Snapshot vor weiterer
           Verfeinerung desselben Distanzbands ein, statt ihn dauerhaft in der Queue zu halten. */
        ChunkManager.LodPriority priority = new ChunkManager.LodPriority(scheduleVersion, band,
                0, 1, dx * dx + dz * dz);
        Runnable retry = () -> {
            this.snapshotInflight.set(false);
            this.releaseWork();
            if (!this.failed && this.publishedChunks.contains(chunkKey)) this.pendingSnapshots.add(chunk);
        };
        boolean accepted = this.chunks.submitLodTask(() -> {
            try {
                if (this.chunks.getChunk(chunk.chunkX, chunk.chunkZ) != chunk
                        || chunk.status != ChunkStatus.READY) {
                    this.publishedChunks.remove(chunkKey);
                    return;
                }
                this.publishLiveVolumes(chunk);
            } catch (OutOfMemoryError error) {
                this.failClosed();
                throw error;
            } catch (Exception error) {
                this.publishedChunks.remove(chunkKey);
                this.logger.warning("Live-Volumenaufnahme fehlgeschlagen: " + chunk.chunkX
                        + ", " + chunk.chunkZ, error);
            } finally {
                this.snapshotInflight.set(false);
                this.releaseWork();
            }
        }, priority, retry);
        if (!accepted) retry.run();
    }

    private void publishLiveVolumes(Chunk chunk) {
        LodVoxelSection[] sections = new LodVoxelSection[Chunk.SECTIONS];
        chunk.readLock().lock();
        try {
            for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                sections[sectionY] = LodVolumeHierarchy.fromChunk(chunk, sectionY);
            }
        } finally {
            chunk.readLock().unlock();
        }

        Set<LodVolumeHierarchy.Key> ancestors = new HashSet<>();
        for (LodVoxelSection section : sections) {
            this.volumes.publish(section);
            int x = section.nodeX, y = section.nodeY, z = section.nodeZ;
            for (int level = 1; level <= LodVoxelSection.MAX_LEVEL; level++) {
                x = Math.floorDiv(x, 2);
                y = Math.floorDiv(y, 2);
                z = Math.floorDiv(z, 2);
                ancestors.add(new LodVolumeHierarchy.Key(x, y, z, level));
            }
        }
        LodVolumeStore.Column live = new LodVolumeStore.Column(
                chunk.chunkX, chunk.chunkZ, 0, sections);
        this.queueWrite(live);
        Set<ColumnKey> ancestorColumns = new HashSet<>();
        for (LodVolumeHierarchy.Key key : ancestors) {
            ancestorColumns.add(new ColumnKey(key.x(), key.z(), key.level()));
        }
        for (ColumnKey columnKey : ancestorColumns) {
            LodVolumeStore.Column column = this.residentColumn(columnKey, true);
            if (column != null) this.queueWrite(column);
        }
        this.revision.incrementAndGet();
    }

    private void failClosed() {
        this.failed = true;
        this.pendingSnapshots.clear();
        this.logger.error("Volumen-LOD-Quelle wegen Speichermangel deaktiviert");
    }

    private void queueWrite(LodVolumeStore.Column column) {
        ColumnKey key = new ColumnKey(column.x(), column.z(), column.level());
        this.knownMissingColumns.remove(key);
        this.dirtyColumns.put(key, column);
        if (this.queuedWrites.add(key)) this.pendingWrites.offer(key);
        this.scheduleWriteDrain();
    }

    private void scheduleWriteDrain() {
        if (!this.writeDrainScheduled.compareAndSet(false, true)) return;
        this.cacheWriter.execute(() -> {
            try {
                ColumnKey key;
                while ((key = this.pendingWrites.poll()) != null) {
                    this.queuedWrites.remove(key);
                    LodVolumeStore.Column column = this.dirtyColumns.remove(key);
                    if (column == null) continue;
                    if (!this.disk.writeColumn(column)) this.dirtyColumns.putIfAbsent(key, column);
                }
                this.disk.flush();
            } finally {
                this.writeDrainScheduled.set(false);
                /* offer() kann genau zwischen dem letzten poll() und dem Flagwechsel landen. */
                if (!this.cacheWriter.isShutdown() && !this.pendingWrites.isEmpty()) {
                    this.scheduleWriteDrain();
                }
            }
        });
    }

    @Override
    public void close() {
        this.pendingSnapshots.clear();
        this.publishedChunks.clear();
        this.knownMissingColumns.clear();
        this.columnLoads.clear();
        this.decorator.clearLodFeatureCache();
        if (!this.pendingWrites.isEmpty()) this.scheduleWriteDrain();
        this.cacheWriter.shutdown();
        try {
            if (!this.cacheWriter.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("LOD-Cache-Writer nach 10 s noch aktiv; Restcache verworfen");
                this.pendingWrites.clear();
                this.queuedWrites.clear();
                this.dirtyColumns.clear();
                this.cacheWriter.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.pendingWrites.clear();
            this.queuedWrites.clear();
            this.dirtyColumns.clear();
            this.cacheWriter.shutdownNow();
        }
        this.disk.close();
    }
}
