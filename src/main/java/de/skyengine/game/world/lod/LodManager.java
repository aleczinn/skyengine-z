package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Koordiniert ausschliesslich das volumetrische LOD-Streaming und dessen Sicht-Handoff. */
public final class LodManager {

    private static final long MAX_PENDING_MESH_BYTES = 32L << 20;

    public record VolumeMeshResult(LodVolumeHierarchy.Key key, int epoch, long requestId,
                                   VoxelLodMesher.Mesh mesh,
                                   long sourceRevision, long completedNanos) {}
    public record VisibleVolumeNode(LodVolumeHierarchy.Key key) {}

    private final Logger logger = LogManager.getLogger(LodManager.class.getName());
    private final PersistentLodDataSource source;
    private final LodBlockAppearance appearance;
    private final ChunkManager chunks;
    private final boolean lodAllowed;
    /* Ein Key bleibt bis zum Konsum seines Resultats reserviert. Wuerde die Reservierung
       bereits am Worker-Ende geloest, koennten bei hoher Render-FPS mehrere Revisionen
       desselben Keys in der Result-Queue liegen und ein altes Mesh fuer einen Frame ein
       neueres ersetzen. */
    private final ConcurrentHashMap<LodVolumeHierarchy.Key, Long> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LodVolumeHierarchy.Key, Long> unavailable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LodVolumeHierarchy.Key, long[]> l0Ownership = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<VolumeMeshResult> results = new ConcurrentLinkedQueue<>();
    private final AtomicLong pendingMeshBytes = new AtomicLong();
    private final AtomicLong nextRequestId = new AtomicLong();
    private final AtomicLong staleMeshResults = new AtomicLong();
    private final AtomicLong duplicateMeshRequests = new AtomicLong();
    private volatile Set<LodVolumeHierarchy.Key> visibleNodes = Set.of();
    private volatile Set<LodVolumeHierarchy.Key> visibleColumns = Set.of();
    private volatile int visibleCoverageVersion;
    private volatile boolean failed;
    /* Der Coverage-Bootstrap ist ein Epoch-Latch: waehrend des ersten Aufbaus darf die
       sichtbare Reichweite nur wachsen. Ein spaeterer Root-Miss (typisch beim L0-Unload)
       darf die bereits sichtbare Welt niemals wieder mit Nahnebel verdecken. */
    private volatile boolean coarseCoverageReady;
    private volatile boolean l0UploadReady;
    private volatile float progressiveFogEnd;
    private volatile int epoch;
    private volatile int scheduleVersion;
    private int playerChunkX, playerChunkZ;
    private int scheduleChunkX = Integer.MIN_VALUE, scheduleChunkZ = Integer.MIN_VALUE;
    private int lastRenderDistance = -1, lastMaxDistance = -1;
    private boolean lastEnabled;

    public LodManager(PersistentLodDataSource source, LodBlockAppearance appearance,
                      ChunkManager chunks, boolean lodAllowed) {
        this.source = source;
        this.appearance = appearance;
        this.chunks = chunks;
        this.lodAllowed = lodAllowed;
    }

    public LodBlockAppearance blockAppearance() { return this.appearance; }

    public void update(EntityPlayer player) {
        this.playerChunkX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.playerChunkZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        GameSettings settings = GameSettings.get();
        boolean enabled = this.lodAllowed && settings.lodEnabled
                && settings.lodMaxDistance > settings.renderDistance
                && !this.failed && this.source.isAvailable();
        if (enabled != this.lastEnabled || settings.renderDistance != this.lastRenderDistance
                || settings.lodMaxDistance != this.lastMaxDistance) {
            this.epoch++;
            this.clearResults();
            this.visibleNodes = Set.of();
            this.visibleColumns = Set.of();
            this.visibleCoverageVersion++;
            this.resetVolumetricCoverage();
            this.scheduleVersion++;
            this.scheduleChunkX = this.playerChunkX;
            this.scheduleChunkZ = this.playerChunkZ;
            this.chunks.updateLodScheduleVersion(this.scheduleVersion);
            this.lastEnabled = enabled;
            this.lastRenderDistance = settings.renderDistance;
            this.lastMaxDistance = settings.lodMaxDistance;
        }
        if (enabled && (Math.abs(this.playerChunkX - this.scheduleChunkX) >= 2
                || Math.abs(this.playerChunkZ - this.scheduleChunkZ) >= 2)) {
            this.scheduleChunkX = this.playerChunkX;
            this.scheduleChunkZ = this.playerChunkZ;
            this.scheduleVersion++;
            /* Nur noch wartende Jobs des alten Kameraankers verwerfen. Sichtbare Meshes und
               die Render-Epoch bleiben erhalten, also entsteht beim Fliegen kein Blank-Frame. */
            this.chunks.updateLodScheduleVersion(this.scheduleVersion);
        }
        /* Live-Snapshots sind wichtig, duerfen aber nicht bereits beim Eintritt einen der nur
           zwei Volumenplaetze vor der groben Sichtabdeckung reservieren. */
        if (enabled && this.l0UploadReady && !this.chunks.isLoadingPaused()) {
            this.source.pumpVolumeSnapshots(this.playerChunkX, this.playerChunkZ,
                    this.scheduleVersion);
        }
    }

    public LodVoxelSection volumeNode(int nodeX, int nodeY, int nodeZ, int level) {
        return this.source.volume(nodeX, nodeY, nodeZ, level);
    }

    public VoxelLodMesher.Mesh meshVolumeNode(int nodeX, int nodeY, int nodeZ, int level,
                                              VoxelLodMesher.MaterialResolver materials) {
        LodVolumeHierarchy.Key key = new LodVolumeHierarchy.Key(nodeX, nodeY, nodeZ, level);
        PersistentLodDataSource.VolumeNeighborhood snapshot = this.source.neighborhood(key);
        return snapshot == null ? null : this.meshVolumeNode(snapshot, materials);
    }

    private VoxelLodMesher.Mesh meshVolumeNode(PersistentLodDataSource.VolumeNeighborhood snapshot,
                                                VoxelLodMesher.MaterialResolver materials) {
        return VoxelLodMesher.mesh(snapshot.center(), materials, snapshot::sample);
    }

    public boolean requestVolumeMesh(LodVolumeHierarchy.Key key,
                                      VoxelLodMesher.MaterialResolver materials,
                                      double distanceSq) {
        return this.requestVolumeMesh(key, materials, distanceSq, false);
    }

    /** Background-Requests waermen von L0 verdeckte Root-Proxies vor, ohne sichtbare
        Frontier-Arbeit aus der begrenzten Worker-Queue zu verdraengen. */
    public boolean requestVolumeMesh(LodVolumeHierarchy.Key key,
                                     VoxelLodMesher.MaterialResolver materials,
                                     double distanceSq, boolean background) {
        long sourceRevision = this.source.meshRevision(key);
        if (!this.volumeAllowed() || !this.l0UploadReady
                || this.pendingMeshBytes.get() >= MAX_PENDING_MESH_BYTES
                || this.unavailable.getOrDefault(key, Long.MIN_VALUE) == sourceRevision
                || !this.source.tryAcquireMeshWork(key.level())) return false;
        long requestId = this.nextRequestId.incrementAndGet();
        if (this.inflight.putIfAbsent(key, requestId) != null) {
            this.duplicateMeshRequests.incrementAndGet();
            this.source.releaseMeshWork(key.level());
            return false;
        }
        int jobEpoch = this.epoch;
        int jobScheduleVersion = this.scheduleVersion;
        int band = Math.max(0, (int) (Math.sqrt(Math.max(0.0, distanceSq)) / 512.0));
        if (background) band = band > Integer.MAX_VALUE - 1_000_000
                ? Integer.MAX_VALUE : band + 1_000_000;
        ChunkManager.LodPriority priority = new ChunkManager.LodPriority(jobScheduleVersion, band,
                Math.max(1, key.level()), 0, distanceSq);
        java.util.concurrent.atomic.AtomicBoolean workReleased = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable releaseWork = () -> {
            if (workReleased.compareAndSet(false, true)) this.source.releaseMeshWork(key.level());
        };
        Runnable discard = () -> {
            this.inflight.remove(key, requestId);
            releaseWork.run();
        };
        boolean accepted = this.chunks.submitLodTask(() -> {
            boolean queuedResult = false;
            try {
                if (jobEpoch != this.epoch || jobScheduleVersion != this.scheduleVersion
                        || !this.volumeAllowed()) return;
                /* Center und sechs Nachbarn werden vor der Revision geladen. Ein warmer
                   Cache-Miss fuellt dadurch nicht waehrend des Meshens seine eigenen Halos
                   und verwirft das fertige Resultat anschliessend als stale. */
                PersistentLodDataSource.VolumeNeighborhood snapshot = this.source.neighborhood(key);
                long buildSourceRevision = snapshot == null
                        ? this.source.meshRevision(key) : snapshot.revision();
                VoxelLodMesher.Mesh mesh = snapshot == null ? null
                        : this.meshVolumeNode(snapshot, materials);
                long currentSourceRevision = this.source.meshRevision(key);
                boolean current = resultStillCurrent(jobEpoch, this.epoch, requestId,
                        this.inflight.getOrDefault(key, Long.MIN_VALUE),
                        buildSourceRevision, currentSourceRevision);
                if (mesh != null && current) {
                    this.unavailable.remove(key);
                    this.pendingMeshBytes.addAndGet(mesh.byteSize());
                    this.results.add(new VolumeMeshResult(key, jobEpoch, requestId, mesh,
                            buildSourceRevision, System.nanoTime()));
                    queuedResult = true;
                } else if (mesh == null && current) {
                    /* Nur ein aktueller echter Cache-Miss darf neue Requests sperren.
                       Ein durch Source-/Ownership-Wechsel veralteter Job muss sofort erneut
                       planbar bleiben, sonst bleibt sein alter Ausschnitt dauerhaft sichtbar. */
                    this.unavailable.put(key, currentSourceRevision);
                }
            } catch (OutOfMemoryError error) {
                this.failClosed();
                throw error;
            } catch (Exception error) {
                this.logger.warning("Volumen-LOD-Mesh fehlgeschlagen: " + key, error);
            } finally {
                if (!queuedResult) this.inflight.remove(key, requestId);
                releaseWork.run();
            }
        }, priority, discard);
        if (!accepted) discard.run();
        return accepted;
    }

    static boolean resultStillCurrent(int jobEpoch, int currentEpoch,
                                      long sourceRevision, long currentSourceRevision) {
        return jobEpoch == currentEpoch && sourceRevision == currentSourceRevision;
    }

    static boolean resultStillCurrent(int jobEpoch, int currentEpoch,
                                      long requestId, long activeRequestId,
                                      long sourceRevision, long currentSourceRevision) {
        return requestId == activeRequestId
                && resultStillCurrent(jobEpoch, currentEpoch, sourceRevision, currentSourceRevision);
    }

    private void failClosed() {
        if (this.failed) return;
        this.failed = true;
        this.clearResults();
        this.visibleNodes = Set.of();
        this.visibleColumns = Set.of();
        this.visibleCoverageVersion++;
        this.resetVolumetricCoverage();
        this.logger.error("Volumen-LOD wegen Speichermangel deaktiviert; kein automatischer Retry");
    }

    public VolumeMeshResult pollVolumeResult() {
        VolumeMeshResult result;
        while ((result = this.results.poll()) != null) {
            this.pendingMeshBytes.addAndGet(-result.mesh.byteSize());
            long activeRequest = this.inflight.getOrDefault(result.key, Long.MIN_VALUE);
            boolean current = resultStillCurrent(result.epoch, this.epoch,
                    result.requestId, activeRequest, result.sourceRevision,
                    this.source.meshRevision(result.key));
            this.inflight.remove(result.key, result.requestId);
            if (current) return result;
            this.staleMeshResults.incrementAndGet();
        }
        return null;
    }

    /** Zweite Aktualitaetspruefung unmittelbar am GPU-Upload. Zwischen Queue-Poll und
        Upload kann ein Live-Snapshot die Quellrevision erneut aendern. */
    public boolean isVolumeResultCurrent(VolumeMeshResult result) {
        return result != null && result.epoch == this.epoch
                && result.sourceRevision == this.source.meshRevision(result.key);
    }

    private void clearResults() {
        VolumeMeshResult result;
        while ((result = this.results.poll()) != null) {
            this.pendingMeshBytes.addAndGet(-result.mesh.byteSize());
            this.inflight.remove(result.key, result.requestId);
        }
        this.inflight.clear();
    }

    public void setVisibleVolumeNodes(Collection<VisibleVolumeNode> visible) {
        Set<LodVolumeHierarchy.Key> nodes = new HashSet<>();
        Set<LodVolumeHierarchy.Key> columns = new HashSet<>();
        for (VisibleVolumeNode node : visible) {
            nodes.add(node.key());
            LodVolumeHierarchy.Key key = node.key();
            columns.add(new LodVolumeHierarchy.Key(key.x(), 0, key.z(), key.level()));
        }
        this.visibleNodes = Set.copyOf(nodes);
        this.visibleColumns = Set.copyOf(columns);
        this.visibleCoverageVersion++;
    }

    static boolean coversChunk(VisibleVolumeNode node, int chunkX, int chunkZ) {
        int chunks = 1 << node.key().level();
        int localX = chunkX - node.key().x() * chunks;
        int localZ = chunkZ - node.key().z() * chunks;
        return (localX | localZ) >= 0 && localX < chunks && localZ < chunks;
    }

    public boolean coversChunk(int cx, int cz) {
        GameSettings settings = GameSettings.get();
        if (!settings.lodEnabled || !this.volumeAllowed()) return true;
        int dx = cx - this.playerChunkX, dz = cz - this.playerChunkZ;
        if ((long) dx * dx + (long) dz * dz
                >= (long) settings.lodMaxDistance * settings.lodMaxDistance) return true;
        /* This method is the unload hand-off gate.  A still-resident L0 chunk owns
           rendering, but its replacement LOD must already cover the column before
           ChunkManager may remove it.  Therefore this deliberately ignores L0
           ownership; lodShowsCell() is the separate render-visibility query. */
        return this.hasVisibleLodCoverage(cx, cz);
    }

    public boolean lodShowsCell(int cx, int cz) {
        /* L0 wird nach dem LOD gezeichnet und besitzt die hoehere Detailtiefe. Sobald sein
           kompletter GPU-Upload bestaetigt ist, darf ein noch grober atomarer LOD-Parent die
           Spalte nicht mehr ausblenden. Der Parent bleibt nur als rueckwaertiger Fallback. */
        return !this.isL0FullyUploaded(cx, cz) && this.hasVisibleLodCoverage(cx, cz);
    }

    public boolean isL0FullyUploaded(int cx, int cz) {
        Chunk chunk = this.chunks.getChunk(cx, cz);
        return chunk != null && chunk.status == de.skyengine.game.world.chunk.ChunkStatus.READY
                && chunk.isFullyUploaded();
    }

    /** Gewuenschte L0-Ownership. pendingUnload nimmt den Chunk bereits aus der Zielmaske,
        obwohl sein altes Mesh bis zum fertigen LOD-Ersatz sichtbar bleiben darf. */
    private boolean l0Owns(int cx, int cz) {
        Chunk chunk = this.chunks.getChunk(cx, cz);
        return chunk != null && !chunk.pendingUnload
                && chunk.status == de.skyengine.game.world.chunk.ChunkStatus.READY
                && chunk.isFullyUploaded();
    }

    /** Meldet einen moeglichen L0-Residency-Wechsel nach Upload oder Radius-Handoff. */
    public void refreshL0Ownership(int cx, int cz) {
        this.setL0Ownership(cx, cz, this.l0Owns(cx, cz));
    }

    /** Entfernt einen Chunk aus allen Zielmasken, bevor sein CPU-Objekt verschwindet. */
    public void clearL0Ownership(int cx, int cz) {
        this.setL0Ownership(cx, cz, false);
    }

    /** Renderer-Wechsel invalidiert alle GPU-Residency auf einmal. */
    public void clearL0Ownership() {
        if (this.l0Ownership.isEmpty()) return;
        this.l0Ownership.clear();
        this.visibleCoverageVersion++;
    }

    private void setL0Ownership(int cx, int cz, boolean owns) {
        boolean[] changedAny = {false};
        for (int level = 0; level <= LodVoxelSection.MAX_LEVEL; level++) {
            int chunks = 1 << level;
            LodVolumeHierarchy.Key key = new LodVolumeHierarchy.Key(
                    Math.floorDiv(cx, chunks), 0, Math.floorDiv(cz, chunks), level);
            int bit = Math.floorMod(cz, chunks) * chunks + Math.floorMod(cx, chunks);
            int word = bit >>> 6;
            long flag = 1L << (bit & 63);
            this.l0Ownership.compute(key, (ignored, previous) -> {
                boolean wasOwned = previous != null && (previous[word] & flag) != 0L;
                if (wasOwned == owns) return previous;
                changedAny[0] = true;
                long[] changed = previous == null ? new long[4] : previous.clone();
                if (owns) changed[word] |= flag;
                else changed[word] &= ~flag;
                return isEmpty(changed) ? null : changed;
            });
        }
        if (changedAny[0]) this.visibleCoverageVersion++;
    }

    public boolean hasL0Ownership(LodVolumeHierarchy.Key key) {
        long[] mask = this.l0Ownership.get(new LodVolumeHierarchy.Key(
                key.x(), 0, key.z(), key.level()));
        return mask != null && !isEmpty(mask);
    }

    public boolean fullyL0Owned(LodVolumeHierarchy.Key key) {
        long[] mask = this.l0Ownership.get(new LodVolumeHierarchy.Key(
                key.x(), 0, key.z(), key.level()));
        if (mask == null) return false;
        int bits = 1 << (key.level() * 2);
        int fullWords = bits >>> 6;
        for (int i = 0; i < fullWords; i++) if (mask[i] != -1L) return false;
        int remainder = bits & 63;
        return remainder == 0 || (mask[fullWords] & (1L << remainder) - 1L)
                == (1L << remainder) - 1L;
    }

    /**
     * Tatsaechliche Render-Abdeckung statt gewuenschter Ownership. pendingUnload wird hier
     * bewusst ignoriert: Bis der Chunk wirklich entfernt wurde, ist sein vollstaendig
     * hochgeladenes L0-Mesh ein gueltiger atomarer Fallback fuer den Coverage-Bootstrap.
     */
    public boolean fullyL0Resident(LodVolumeHierarchy.Key key) {
        int chunks = 1 << key.level();
        int baseX = key.x() * chunks;
        int baseZ = key.z() * chunks;
        for (int z = 0; z < chunks; z++) {
            for (int x = 0; x < chunks; x++) {
                if (!this.isL0FullyUploaded(baseX + x, baseZ + z)) return false;
            }
        }
        return true;
    }

    private static boolean isEmpty(long[] words) {
        for (long word : words) if (word != 0L) return false;
        return true;
    }

    public int playerChunkX() { return this.playerChunkX; }
    public int playerChunkZ() { return this.playerChunkZ; }
    public int visibleCoverageVersion() { return this.visibleCoverageVersion; }

    private boolean hasVisibleLodCoverage(int cx, int cz) {
        Set<LodVolumeHierarchy.Key> columns = this.visibleColumns;
        for (int level = 0; level <= LodVoxelSection.MAX_LEVEL; level++) {
            int chunks = 1 << level;
            if (columns.contains(new LodVolumeHierarchy.Key(Math.floorDiv(cx, chunks), 0,
                    Math.floorDiv(cz, chunks), level))) return true;
        }
        return false;
    }

    public int visibleStateAt(int x, int y, int z) {
        long voxel = this.visibleVoxelAt(x, y, z);
        return voxel == Long.MIN_VALUE ? -1 : LodVoxel.coverage(voxel) == 0
                ? Blocks.AIR : LodVoxel.stateId(voxel);
    }

    public int visibleSkyLightAt(int x, int y, int z) {
        if (y >= Chunk.HEIGHT) return 15;
        if (y < 0) return 0;
        long voxel = this.visibleVoxelAt(x, y, z);
        return voxel == Long.MIN_VALUE ? -1 : LodVoxel.sky(voxel);
    }

    private long visibleVoxelAt(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return 0L;
        if (!this.hasVisibleLodCoverage(Math.floorDiv(x, ChunkSection.SIZE),
                Math.floorDiv(z, ChunkSection.SIZE))) return Long.MIN_VALUE;
        Set<LodVolumeHierarchy.Key> nodes = this.visibleNodes;
        for (int level = 0; level <= LodVoxelSection.MAX_LEVEL; level++) {
            int extent = LodVoxelSection.SIZE << level;
            LodVolumeHierarchy.Key key = new LodVolumeHierarchy.Key(Math.floorDiv(x, extent),
                    Math.floorDiv(y, extent), Math.floorDiv(z, extent), level);
            if (!nodes.contains(key)) continue;
            LodVoxelSection section = this.source.residentVolume(key);
            if (section == null) return Long.MIN_VALUE;
            int cell = 1 << level;
            return section.get(Math.floorMod(x, extent) / cell,
                    Math.floorMod(y, extent) / cell, Math.floorMod(z, extent) / cell);
        }
        return Long.MIN_VALUE;
    }

    public int volumeEpoch() { return this.epoch; }
    public boolean volumeAllowed() {
        GameSettings settings = GameSettings.get();
        return !this.failed && this.lodAllowed && settings.lodEnabled
                && settings.lodMaxDistance > settings.renderDistance && this.source.isAvailable();
    }
    public int volumeNodeCount() { return this.source.volumeNodeCount(); }
    public long volumeEstimatedBytes() {
        return this.source.volumeEstimatedBytes() + Math.max(0L, this.pendingMeshBytes.get());
    }
    public int volumeActiveBuilds() { return this.source.activeWorkCount(); }
    public int volumeMaxBuilds() { return this.source.maxActiveWorkCount(); }
    public long volumeCacheHits() { return this.source.cacheHitCount(); }
    public long volumeCacheMisses() { return this.source.cacheMissCount(); }
    public long volumeGeneratedColumns() { return this.source.generatedColumns(); }
    public int volumeDirtyColumns() { return this.source.dirtyColumnCount(); }
    public long volumeStaleMeshResults() { return this.staleMeshResults.get(); }
    public long volumeDuplicateMeshRequests() { return this.duplicateMeshRequests.get(); }
    public long volumeMeshRevision(LodVolumeHierarchy.Key key) { return this.source.meshRevision(key); }
    /** Setzt ausschliesslich bei einer neuen LOD-Epoch bzw. echter Deaktivierung zurueck. */
    public synchronized void resetVolumetricCoverage() {
        this.coarseCoverageReady = false;
        this.progressiveFogEnd = 0F;
    }

    /**
     * Monotone Bootstrap-Reichweite. complete ist ein Latch und kann innerhalb derselben
     * Epoch nicht wieder geloescht werden. safeFloor ist die garantiert residente L0-Reichweite.
     */
    public synchronized void advanceVolumetricCoverage(float safeFloor, float candidateEnd,
                                                        boolean complete) {
        if (this.coarseCoverageReady) return;
        this.progressiveFogEnd = nextProgressiveFogEnd(
                this.progressiveFogEnd, safeFloor, candidateEnd);
        if (complete) this.coarseCoverageReady = true;
    }

    static float nextProgressiveFogEnd(float previous, float safeFloor, float candidateEnd) {
        float candidate = Float.isFinite(candidateEnd) ? candidateEnd : safeFloor;
        return Math.max(Math.max(0F, previous), Math.max(safeFloor, candidate));
    }

    public synchronized float effectiveFogEnd(float configuredBlocks) {
        float safeFloor = GameSettings.get().renderDistance * (float) ChunkSection.SIZE;
        return effectiveFogEnd(this.coarseCoverageReady, this.progressiveFogEnd,
                safeFloor, configuredBlocks);
    }

    static float effectiveFogEnd(boolean ready, float progressiveEnd,
                                 float safeFloor, float configuredEnd) {
        return ready ? configuredEnd
                : Math.min(configuredEnd, Math.max(safeFloor, progressiveEnd));
    }

    public boolean isVolumetricCoverageReady() { return this.coarseCoverageReady; }
    public void setL0UploadReady(boolean ready) { this.l0UploadReady = ready; }
    public boolean isL0UploadReady() { return this.l0UploadReady; }
}
