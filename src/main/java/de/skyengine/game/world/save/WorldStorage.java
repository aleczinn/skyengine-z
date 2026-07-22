package de.skyengine.game.world.save;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Chunk-Store einer Welt: bildet Chunk-Koordinaten auf Region-Dateien
 * ({@code region/r.<rx>.<rz>.srg}, 16×16 Chunks) ab und verwaltet die offenen Handles.
 *
 * <p>Single-Writer-Disziplin: alle Methoden sind {@code synchronized} — ein Lock genügt,
 * Region-IO ist ms-skalig und die Aufrufer (Chunk-Worker lesend, IO-Thread schreibend)
 * kontendieren praktisch nie. Der HÄUFIGE Fall „Chunk nie gespeichert" (unmodifizierte
 * Chunks generierter Welten) kostet über {@code missingRegions} nur einen Set-Lookup.
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

    public WorldStorage(File regionDir) {
        this.regionDir = regionDir;
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

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
                /* Schreibpfad: Fehler weiterreichen wäre Datenverlust ohne Diagnose — loggen,
                   der Save des Chunks schlägt fehl und bleibt über das modified-Flag erhalten. */
                this.logger.error("Region-Datei (" + rx + ", " + rz + ") nicht öffnbar", e);
            } else {
                this.logger.warning("Region-Datei (" + rx + ", " + rz + ") nicht lesbar — Chunks werden regeneriert", e);
                this.missingRegions.add(key);
            }
            return null;
        }
    }

    /** Schließt alle Region-Handles. */
    public synchronized void close() {
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
