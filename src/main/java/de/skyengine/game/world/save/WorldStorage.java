package de.skyengine.game.world.save;

import de.skyengine.game.world.World;
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
 * {@code World.dispose()} NACH {@code chunkManager.dispose()}.
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

    private final World world;
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

    /* Invalidiert z.B. den LOD-Heightmap-Cache importierter Welten (StorageLodDataSource). */
    private volatile ChunkWriteListener writeListener;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Chunk IO");
        thread.setDaemon(true);
        return thread;
    });

    /* Offene Save-Jobs (eingereiht, aber noch nicht geschrieben) — für die „Spiel gespeichert"-
       Meldung, die erst erscheinen soll, wenn wirklich alles auf der Platte liegt. */
    private final AtomicInteger pendingSaves = new AtomicInteger();

    public WorldStorage(File regionDir, World world, WorldGenerator generator,
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
        chunk.grassTintCorners = null;
        chunk.foliageTintCorners = null;
        chunk.pendingScheduledTicks = null;
    }

    /* --- Save-Pfad (Tick-Thread reiht ein, IO-Thread schreibt) --- */

    /**
     * Reiht einen Save des Chunks ein. NUR Tick-Thread (Unload, Autosave, Exit, F8) —
     * hier wird auch der Scheduled-Tick-Snapshot gezogen (die Queue ist tick-thread-only);
     * dadurch sind ALLE Enqueue-Orte automatisch korrekt. Der Aufrufer hat
     * {@code saveQueued} bereits gesetzt; der Job snapshottet die Blöcke unter dem
     * Read-Lock, schreibt off-Lock und löscht die Flags. Schreibfehler setzen
     * {@code modified} zurück — der nächste Trigger versucht es erneut.
     *
     * <p>Tick- und Block-Snapshot können minimal auseinanderliegen: ein dazwischen
     * gefeuerter Tick ist doppelt im Save und feuert nach dem Laden einmal ins Leere —
     * Ticks sind Zustands-Neubewertungen, das ist harmlos.
     */
    public void enqueueSave(Chunk chunk) {
        chunk.scheduledTickSnapshot = this.world != null ? this.world.snapshotScheduledTicks(chunk) : null;
        /* BlockEntity-Zustand JETZT (Tick-Thread) vorserialisieren — der IO-Thread darf be.save()
           nicht auf dem Live-Zustand aufrufen (Race mit GUI-Mutationen, z.B. Truhen-Inventar). */
        chunk.blockEntitySnapshot = ChunkSerializer.snapshotBlockEntities(chunk);
        this.pendingSaves.incrementAndGet();
        try {
            /* Das Dekrement gehört ins finally des Jobs, nicht ans Ende von saveNow: saveNow hat
               einen frühen return (Chunk nicht mehr modified) und einen Fehlerpfad. */
            this.ioExecutor.execute(() -> {
                try {
                    this.saveNow(chunk);
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

    private void saveNow(Chunk chunk) {
        byte[] payload;
        chunk.readLock().lock();
        try {
            List<SavedTick> ticks = chunk.scheduledTickSnapshot;
            chunk.scheduledTickSnapshot = null;
            List<SavedBlockEntity> blockEntities = chunk.blockEntitySnapshot;
            chunk.blockEntitySnapshot = null;
            if (!chunk.modified) {
                chunk.saveQueued = false;
                return;
            }
            payload = ChunkSerializer.serialize(chunk, this.generatorId, this.generatorVersion, this.storeTints, ticks, blockEntities);
            chunk.modified = false;
        } finally {
            chunk.readLock().unlock();
        }
        try {
            this.writeChunk(chunk.chunkX, chunk.chunkZ, payload);
        } catch (Exception e) {
            chunk.modified = true;
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
                /* Schreibpfad: loggen, der Save schlägt fehl und bleibt über modified erhalten. */
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
     * In {@code World.dispose()} NACH {@code chunkManager.dispose()} aufrufen — danach
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
