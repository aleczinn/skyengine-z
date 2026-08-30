package de.skyengine.game.world.save;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk-Store einer Welt: bildet Chunk-Koordinaten auf Region-Dateien
 * ({@code region/r.<rx>.<rz>.srg}, 16×16 Chunks) ab, verwaltet die offenen Handles und
 * besitzt den einzigen Schreib-Thread ("Chunk IO").
 *
 * <p>Single-Writer-Disziplin: alle Datei-Methoden sind {@code synchronized} — ein Lock genügt,
 * Region-IO ist ms-skalig und die Aufrufer (Chunk-Worker lesend, IO-Thread schreibend)
 * kontendieren praktisch nie. Der HÄUFIGE Fall „Chunk nie gespeichert" (unmodifizierte
 * Chunks generierter Welten) kostet über {@code missingRegions} nur einen Set-Lookup.
 *
 * <p>Save-Jobs laufen bewusst NICHT auf dem Chunk-Worker-Pool: dessen dispose() macht
 * {@code shutdownNow} und würde wartende Saves verwerfen (Datenverlust). Der eigene
 * IO-Executor wird in {@link #close()} per shutdown + await geflusht — Aufruf in
 * {@code Dimension.dispose()} NACH {@code chunkManager.dispose()}.
 */
public class WorldStorage {

    /* 16 Chunks pro Region-Achse -> Region-Koordinate = chunkCoord >> 4. */
    private static final int REGION_SHIFT = 4;
    private static final int REGION_MASK = 15;

    private final Logger logger = LogManager.getLogger(WorldStorage.class.getName());

    private final File regionDir;
    private final Map<Long, RegionFile> regions = new HashMap<>();
    /* Regionen, deren Datei nicht existiert — Cache für den häufigen Miss-Fall. */
    private final Set<Long> missingRegions = new HashSet<>();

    private final Dimension world;
    private final WorldGenerator generator;
    private final String generatorId;
    private final int generatorVersion;
    /* Importierte Welten speichern ihre Tint-Grids (MC-Biome != Engine-Biome); generierte
       berechnen sie beim Laden über generator.fillTintCorners neu (bleibt dynamisch). */
    private final boolean storeTints;

    /** Callback nach jedem erfolgreichen Chunk-Write (läuft auf dem IO-Thread). */
    public interface ChunkWriteListener {
        void onChunkWritten(int chunkX, int chunkZ);
    }

    /* Revision gespeicherter Chunkdaten, u.a. für davon abgeleitete Fernrepräsentationen. */
    private volatile ChunkWriteListener writeListener;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Chunk IO");
        thread.setDaemon(true);
        return thread;
    });

    /* Offene Save-Jobs (eingereiht, aber noch nicht geschrieben) — für die „Spiel gespeichert"-
       Meldung, die erst erscheinen soll, wenn wirklich alles auf der Platte liegt. */
    private final AtomicInteger pendingSaves = new AtomicInteger();

    /** Vollstaendig vom Live-Chunk entkoppelter Stand eines Enqueue-Zeitpunkts. */
    private record SaveSnapshot(Chunk data, long epoch, List<SavedTick> ticks,
                                List<SavedBlockEntity> blockEntities,
                                List<SavedEntity> entities) {}

    public WorldStorage(File regionDir, Dimension world, WorldGenerator generator,
                        String generatorId, int generatorVersion, boolean storeTints) {
        this.regionDir = regionDir;
        this.world = world;
        this.generator = generator;
        this.generatorId = generatorId;
        this.generatorVersion = generatorVersion;
        this.storeTints = storeTints;
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /* --- Lade-Pfad (Chunk-Worker) --- */

    /**
     * Lädt den Chunk aus seinem Snapshot. false bei „nicht vorhanden" ODER Fehler (geloggt) —
     * der Aufrufer generiert dann. Läuft auf einem Chunk-Worker, BEVOR der Chunk per
     * Status-Publish lesbar wird (gleicher Vertrag wie generator.generate).
     */
    public boolean loadChunk(Chunk chunk) {
        byte[] payload = this.readChunk(chunk.chunkX, chunk.chunkZ);
        if (payload == null) return false;
        try {
            ChunkSerializer.deserialize(chunk, payload, this.world);
        } catch (Exception e) {
            this.logger.warning("Chunk (" + chunk.chunkX + ", " + chunk.chunkZ
                    + ") nicht deserialisierbar — wird regeneriert", e);
            clearPartialLoad(chunk);
            return false;
        }
        /* Generierte Welten: Grids nicht im Payload -> über denselben Codepfad wie generate()
           neu berechnen (pur, threadsicher). Importierte Welten haben sie im Payload. */
        if (chunk.grassTintCorners == null) {
            this.generator.fillTintCorners(chunk);
        }
        return true;
    }

    /* Halb befüllte Chunks vor dem Generator-Fallback restlos leeren (der Generator startet
       auf einem leeren Chunk und überschreibt Luft-Zellen nicht). */
    private static void clearPartialLoad(Chunk chunk) {
        for (int s = 0; s < Chunk.SECTIONS; s++) chunk.installSection(s, null);
        chunk.blockEntities().clear();
        chunk.entities().clear();
        chunk.grassTintCorners = null;
        chunk.foliageTintCorners = null;
        chunk.pendingScheduledTicks = null;
    }

    /* --- Save-Pfad (Tick-Thread reiht ein, IO-Thread schreibt) --- */

    /**
     * Reiht einen Save des Chunks ein. NUR Tick-Thread (Unload, Autosave, Exit, F8) —
     * hier wird auch der Scheduled-Tick-Snapshot gezogen (die Queue ist tick-thread-only);
     * dadurch sind ALLE Enqueue-Orte automatisch korrekt. Der Aufrufer hat
     * {@code saveQueued} bereits gesetzt. Dieser Aufruf kopiert Blockdaten, Tints,
     * BlockEntities und Scheduled-Ticks unter einem Read-Lock in einen unveränderlichen
     * Snapshot. Serialisierung, Kompression und Datei-IO laufen danach ausschließlich auf
     * dem IO-Thread. Erst ein erfolgreicher Write bestätigt exakt die Snapshot-Epoch.
     */
    public void enqueueSave(Chunk chunk) {
        SaveSnapshot snapshot = this.captureSnapshot(chunk);
        if (snapshot == null) {
            chunk.saveQueued = false;
            return;
        }
        this.pendingSaves.incrementAndGet();
        try {
            /* Das Dekrement gehört ins finally des Jobs, damit auch der Fehlerpfad erfasst ist. */
            this.ioExecutor.execute(() -> {
                try {
                    this.saveNow(chunk, snapshot);
                } finally {
                    this.pendingSaves.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            /* close() lief bereits — darf nur nach dem letzten Trigger passieren. */
            this.pendingSaves.decrementAndGet();
            chunk.saveQueued = false;
            this.logger.error("Save-Job nach Storage-Close verworfen: Chunk ("
                    + chunk.chunkX + ", " + chunk.chunkZ + ")");
        }
    }

    /** true, solange noch Save-Jobs eingereiht oder in Arbeit sind (jeder Thread). */
    public boolean hasPendingSaves() {
        return this.pendingSaves.get() > 0;
    }

    private SaveSnapshot captureSnapshot(Chunk chunk) {
        chunk.readLock().lock();
        try {
            if (!chunk.isModified()) return null;
            List<SavedTick> ticks = this.world == null ? null : this.world.snapshotScheduledTicks(chunk);
            if (ticks != null) ticks = List.copyOf(ticks);
            List<SavedBlockEntity> blockEntities = List.copyOf(ChunkSerializer.snapshotBlockEntities(chunk));
            List<SavedEntity> entities = List.copyOf(ChunkSerializer.snapshotEntities(chunk));
            Chunk data = ChunkSerializer.snapshotChunkData(chunk);
            return new SaveSnapshot(data, chunk.modificationEpoch(), ticks, blockEntities, entities);
        } finally {
            chunk.readLock().unlock();
        }
    }

    private void saveNow(Chunk chunk, SaveSnapshot snapshot) {
        try {
            byte[] payload = ChunkSerializer.serialize(snapshot.data(), this.generatorId,
                    this.generatorVersion, this.storeTints, snapshot.ticks(), snapshot.blockEntities(),
                    snapshot.entities());
            this.writeChunk(chunk.chunkX, chunk.chunkZ, payload);
            chunk.markSaved(snapshot.epoch());
        } catch (Exception e) {
            this.logger.error("Chunk (" + chunk.chunkX + ", " + chunk.chunkZ
                    + ") konnte nicht gespeichert werden", e);
        } finally {
            chunk.saveQueued = false;
        }
    }

    /* --- Region-Datei-Zugriff (synchronized Single-Writer) --- */

    /** true, wenn für den Chunk ein Snapshot existiert (nur Header-/Set-Lookup, kein IO). */
    public synchronized boolean hasChunk(int chunkX, int chunkZ) {
        RegionFile region = region(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT, false);
        return region != null && region.has(chunkX & REGION_MASK, chunkZ & REGION_MASK);
    }

    /**
     * Liest den rohen Chunk-Payload oder null (kein Snapshot vorhanden ODER Eintrag korrupt —
     * korrupte Einträge werden geloggt, der Aufrufer fällt auf Regeneration zurück).
     */
    public synchronized byte[] readChunk(int chunkX, int chunkZ) {
        RegionFile region = region(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT, false);
        if (region == null) return null;
        try {
            return region.read(chunkX & REGION_MASK, chunkZ & REGION_MASK);
        } catch (IOException e) {
            this.logger.warning("Chunk (" + chunkX + ", " + chunkZ + ") nicht lesbar — wird regeneriert", e);
            return null;
        }
    }

    /** Schreibt einen rohen Chunk-Payload (Region-Datei wird bei Bedarf angelegt). */
    public synchronized void writeChunk(int chunkX, int chunkZ, byte[] rawPayload) throws IOException {
        RegionFile region = region(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT, true);
        if (region == null) {
            throw new IOException("Region-Datei für Chunk (" + chunkX + ", " + chunkZ + ") nicht öffnbar");
        }
        region.write(chunkX & REGION_MASK, chunkZ & REGION_MASK, rawPayload);
        ChunkWriteListener listener = this.writeListener;
        if (listener != null) listener.onChunkWritten(chunkX, chunkZ);
    }

    public void setWriteListener(ChunkWriteListener listener) {
        this.writeListener = listener;
    }

    private RegionFile region(int rx, int rz, boolean create) {
        long key = regionKey(rx, rz);
        RegionFile cached = this.regions.get(key);
        if (cached != null) return cached;
        if (!create && this.missingRegions.contains(key)) return null;

        File path = new File(this.regionDir, "r." + rx + "." + rz + ".srg");
        if (!path.exists() && !create) {
            this.missingRegions.add(key);
            return null;
        }
        try {
            if (create && !this.regionDir.exists() && !this.regionDir.mkdirs()) {
                throw new IOException("Region-Verzeichnis nicht anlegbar: " + this.regionDir);
            }
            RegionFile region = new RegionFile(path);
            this.regions.put(key, region);
            this.missingRegions.remove(key);
            return region;
        } catch (IOException e) {
            if (create) {
                /* Schreibpfad: loggen; ohne Epoch-Bestätigung bleibt der Chunk dirty. */
                this.logger.error("Region-Datei (" + rx + ", " + rz + ") nicht öffnbar", e);
            } else {
                this.logger.warning("Region-Datei (" + rx + ", " + rz + ") nicht lesbar — Chunks werden regeneriert", e);
                this.missingRegions.add(key);
            }
            return null;
        }
    }

    /**
     * Flusht alle ausstehenden Save-Jobs (bis 10 s) und schließt die Region-Handles.
     * In {@code Dimension.dispose()} NACH {@code chunkManager.dispose()} aufrufen — danach
     * schreibt kein Worker mehr auf Chunks.
     */
    public void close() {
        this.ioExecutor.shutdown();
        try {
            if (!this.ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.error("Chunk-IO-Flush nach 10 s abgebrochen — es können Chunk-Saves fehlen!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            for (RegionFile region : this.regions.values()) {
                try {
                    region.close();
                } catch (IOException e) {
                    this.logger.error("Region-Datei ließ sich nicht schließen", e);
                }
            }
            this.regions.clear();
            this.missingRegions.clear();
        }
    }
}
