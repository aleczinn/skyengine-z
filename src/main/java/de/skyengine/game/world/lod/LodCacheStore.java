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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Persistenter, versionierter LOD-Store mit gebuendeltem Hintergrundschreiber. */
final class LodCacheStore implements AutoCloseable {

    private record Pending(int chunkX, int chunkZ, ChunkLodColumns columns) {}

    private static final int MAGIC = 0x4C4F4434; // LOD4, gewichtete Intervall-Abdeckung
    private static final int REGION_SHIFT = 4;
    private static final int REGION_MASK = 15;
    private static final int MAX_PENDING = 256;

    private final Logger logger = LogManager.getLogger(LodCacheStore.class.getName());
    private final File directory;
    private final int fingerprint;
    private final Map<Long, RegionFile> regions = new HashMap<>();
    private final Set<Long> missing = new HashSet<>();
    private final ArrayBlockingQueue<Long> queue = new ArrayBlockingQueue<>(MAX_PENDING);
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong droppedWrites = new AtomicLong();
    private final ReentrantLock ioLock = new ReentrantLock();
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
        /* Cache ist nur eine Beschleunigung: Ein laufender Write darf keinen LOD-Worker
           festhalten. In dem Fall baut der Worker die ableitbaren Daten direkt neu. */
        if (!this.ioLock.tryLock()) return null;
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
            this.ioLock.unlock();
        }
    }

    /** Nicht-blockierend; mehrere Level desselben Chunks werden vor dem Schreiben zusammengelegt. */
    void writeLater(int chunkX, int chunkZ, ChunkLodColumns columns) {
        if (this.closing) return;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        Pending replacement = new Pending(chunkX, chunkZ, columns);
        Pending previous = this.pending.put(key, replacement);
        if (previous != null) return;
        if (!this.queue.offer(key)) {
            this.pending.remove(key, replacement);
            this.droppedWrites.incrementAndGet();
        }
    }

    long droppedWrites() { return this.droppedWrites.get(); }

    private void writerLoop() {
        try {
            while (!this.closing || !this.queue.isEmpty()) {
                try {
                Long key = this.queue.poll(100, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                Pending item = this.pending.remove(key);
                if (item != null) writeNow(item);
                } catch (InterruptedException e) {
                    if (!this.closing) Thread.currentThread().interrupt();
                }
            }
        } finally {
            closeRegions();
        }
    }

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
            this.ioLock.lock();
            try {
                RegionFile region = region(item.chunkX >> REGION_SHIFT, item.chunkZ >> REGION_SHIFT, true);
                if (region != null) region.write(item.chunkX & REGION_MASK, item.chunkZ & REGION_MASK,
                        bytes.toByteArray());
            } finally {
                this.ioLock.unlock();
            }
        } catch (IOException e) {
            this.logger.warning("LOD-Cache fuer Chunk (" + item.chunkX + ", " + item.chunkZ + ") nicht schreibbar", e);
        }
    }

    private RegionFile region(int rx, int rz, boolean create) {
        long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
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
            this.writer.interrupt();
            try {
                this.writer.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.pending.clear();
        this.queue.clear();
    }

    private void closeRegions() {
        this.ioLock.lock();
        try {
            for (RegionFile region : this.regions.values()) {
                try { region.close(); }
                catch (IOException e) { this.logger.warning("LOD-Region konnte nicht geschlossen werden", e); }
            }
            this.regions.clear();
            this.missing.clear();
        } finally {
            this.ioLock.unlock();
        }
    }
}
