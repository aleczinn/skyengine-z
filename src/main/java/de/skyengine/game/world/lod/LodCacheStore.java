package de.skyengine.game.world.lod;

import de.skyengine.game.world.save.RegionFile;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Persistenter, versionierter LOD-Store mit gebuendeltem Hintergrundschreiber. */
final class LodCacheStore implements AutoCloseable {

    private record Pending(int chunkX, int chunkZ, ChunkLodColumns columns) {}

    private static final class RegionPending {
        final ConcurrentLinkedQueue<Long> chunks = new ConcurrentLinkedQueue<>();
        final AtomicBoolean queued = new AtomicBoolean();
    }

    private static final int MAGIC = 0x4C443132; // LD12, korrekte Bedrock-nahe Terrainhuelle
    private static final int REGION_SHIFT = 4;
    private static final int REGION_MASK = 15;
    private static final int MAX_REGION_WRITE_BATCH = 16;

    private final Logger logger = LogManager.getLogger(LodCacheStore.class.getName());
    private final File directory;
    private final int fingerprint;
    private final Map<Long, RegionFile> regions = new HashMap<>();
    private final Set<Long> missing = new HashSet<>();
    private final LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, RegionPending> pendingRegions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ReentrantLock> regionLocks = new ConcurrentHashMap<>();
    private final AtomicLong droppedWrites = new AtomicLong();
    private final Thread writer;
    private volatile boolean closing;

    LodCacheStore(File directory, int generatorVersion, int featureFingerprint) {
        this.directory = directory;
        int hash = 31 * generatorVersion + featureFingerprint;
        this.fingerprint = 31 * hash + LodBlockRules.fingerprint();
        this.writer = new Thread(this::writerLoop, "LOD-Cache-Writer");
        this.writer.setDaemon(true);
        this.writer.setPriority(Thread.MIN_PRIORITY);
        this.writer.start();
    }

    ChunkLodColumns read(int chunkX, int chunkZ) {
        long regionKey = regionKey(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        ReentrantLock lock = this.regionLocks.computeIfAbsent(regionKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            RegionFile region = region(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT, false);
            if (region == null) return null;
            byte[] payload = region.read(chunkX & REGION_MASK, chunkZ & REGION_MASK);
            if (payload == null) return null;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != MAGIC || in.readInt() != this.fingerprint) return null;
            int mask = in.readUnsignedByte();
            LodColumn[][] levels = new LodColumn[ChunkLodColumns.LEVELS][];
            for (int level = 0; level < levels.length; level++) {
                if ((mask & (1 << level)) == 0) continue;
                int length = in.readInt();
                int side = 32 >> level;
                if (length != side * side) return null;
                levels[level] = new LodColumn[length];
                for (int i = 0; i < length; i++) {
                    int count = in.readUnsignedByte();
                    if (count > LodColumn.MAX_INTERVALS) return null;
                    long[] intervals = new long[count];
                    for (int j = 0; j < count; j++) intervals[j] = in.readLong();
                    levels[level][i] = count == 0 ? LodColumn.EMPTY : new LodColumn(intervals);
                }
            }
            return ChunkLodColumns.fromLevels(levels);
        } catch (Exception e) {
            this.logger.warning("LOD-Cache fuer Chunk (" + chunkX + ", " + chunkZ + ") nicht lesbar", e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Nicht-blockierend; mehrere Level desselben Chunks werden vor dem Schreiben zusammengelegt. */
    void writeLater(int chunkX, int chunkZ, ChunkLodColumns columns) {
        if (this.closing) return;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        Pending replacement = new Pending(chunkX, chunkZ, columns);
        Pending previous = this.pending.put(key, replacement);
        if (previous != null) return;
        long regionKey = regionKey(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        RegionPending region = this.pendingRegions.computeIfAbsent(regionKey, ignored -> new RegionPending());
        region.chunks.add(key);
        this.scheduleRegion(regionKey, region);
    }

    private void scheduleRegion(long regionKey, RegionPending region) {
        if (region.queued.compareAndSet(false, true)) this.queue.offer(regionKey);
    }

    long droppedWrites() { return this.droppedWrites.get(); }

    private void writerLoop() {
        try {
            while (!this.closing || !this.queue.isEmpty()) {
                try {
                Long regionKey = this.queue.poll(100, TimeUnit.MILLISECONDS);
                if (regionKey == null) continue;
                this.writeRegion(regionKey);
                } catch (InterruptedException e) {
                    if (!this.closing) Thread.currentThread().interrupt();
                }
            }
        } finally {
            closeRegions();
        }
    }

    private void writeRegion(long regionKey) {
        RegionPending regionPending = this.pendingRegions.get(regionKey);
        if (regionPending == null) return;
        ReentrantLock lock = this.regionLocks.computeIfAbsent(regionKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Long chunkKey;
            int writes = 0;
            while (writes++ < MAX_REGION_WRITE_BATCH
                    && (chunkKey = regionPending.chunks.poll()) != null) {
                Pending item = this.pending.remove(chunkKey);
                if (item != null) this.writeNow(item);
            }
        } finally {
            lock.unlock();
            regionPending.queued.set(false);
            if (!regionPending.chunks.isEmpty()) this.scheduleRegion(regionKey, regionPending);
        }
    }

    /** Aufgerufen mit bereits gehaltenem Regions-Lock. */
    private void writeNow(Pending item) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(12 * 1024);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(this.fingerprint);
            int mask = item.columns.levelMask();
            out.writeByte(mask);
            for (int level = 0; level < ChunkLodColumns.LEVELS; level++) {
                if ((mask & (1 << level)) == 0) continue;
                LodColumn[] data = item.columns.level(level);
                out.writeInt(data.length);
                for (LodColumn column : data) {
                    out.writeByte(column.size());
                    for (int i = 0; i < column.size(); i++) out.writeLong(column.interval(i));
                }
            }
            out.flush();
            RegionFile region = region(item.chunkX >> REGION_SHIFT, item.chunkZ >> REGION_SHIFT, true);
            if (region != null) region.write(item.chunkX & REGION_MASK, item.chunkZ & REGION_MASK,
                    bytes.toByteArray());
        } catch (IOException e) {
            this.logger.warning("LOD-Cache fuer Chunk (" + item.chunkX + ", " + item.chunkZ + ") nicht schreibbar", e);
        }
    }

    private synchronized RegionFile region(int rx, int rz, boolean create) {
        long key = regionKey(rx, rz);
        RegionFile cached = this.regions.get(key);
        if (cached != null) return cached;
        if (!create && this.missing.contains(key)) return null;
        File path = new File(this.directory, "r." + rx + "." + rz + ".srg");
        if (!path.exists() && !create) {
            this.missing.add(key);
            return null;
        }
        try {
            if (create && !this.directory.exists() && !this.directory.mkdirs()) {
                throw new IOException("LOD-Verzeichnis nicht anlegbar: " + this.directory);
            }
            RegionFile region = new RegionFile(path, false);
            this.regions.put(key, region);
            this.missing.remove(key);
            return region;
        } catch (IOException e) {
            this.logger.warning("LOD-Region (" + rx + ", " + rz + ") nicht zugreifbar", e);
            return null;
        }
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    @Override
    public void close() {
        this.closing = true;
        try {
            this.writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (this.writer.isAlive()) {
            /* Cache-Daten sind ableitbar: nach dem vereinbarten Zeitbudget lieber verwerfen,
               als den Welt-Exit an einem langsamen Datentraeger festzuhalten. */
            this.queue.clear();
            this.pending.clear();
            this.pendingRegions.clear();
            this.writer.interrupt();
            try {
                this.writer.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.pending.clear();
        this.pendingRegions.clear();
        this.queue.clear();
    }

    private synchronized void closeRegions() {
            for (RegionFile region : this.regions.values()) {
                try { region.close(); }
                catch (IOException e) { this.logger.warning("LOD-Region konnte nicht geschlossen werden", e); }
            }
            this.regions.clear();
            this.missing.clear();
            this.regionLocks.clear();
    }
}
