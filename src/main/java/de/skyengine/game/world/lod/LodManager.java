package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import de.skyengine.utils.collect.LongIntMap;
import de.skyengine.utils.collect.LongObjMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Verwaltet die blockbasierten Heightmap-LOD-Ringe um den Spieler (Clipmap-Prinzip):
 * L0 = echte Chunks bis renderDistance, darüber formelbasierte Level bis lodMaxDistance
 * (siehe {@link LodConfig}).
 *
 * <p><b>Nahtlose L0-Grenze:</b> Jeder Mesh-Job trägt eine 16-Bit-Maske über die 4×4 Chunks
 * seiner Region — geclippt werden genau die Zellen, deren Chunk JETZT echtes Terrain zeigt
 * (Status READY). Ändert sich die Maske (Chunk fertig geladen/entladen), remesht die Region.
 *
 * <p><b>Hysterese:</b> Die Level-Zuordnung hängt am Anker (Zentrum der Spieler-Region des
 * letzten Recompute); neu berechnet wird erst, wenn der Spieler die Regionshälfte plus
 * Puffer verlässt — kein Level-Pop-Pendeln an Regionsgrenzen.
 *
 * <p>Datenquelle ist ausschließlich die abstrahierte {@link LodDataSource} — LOD kann
 * Spieleränderungen weder überschreiben noch verzögern. Tick-getrieben aus
 * {@code World.update()} (Tick und Render laufen auf demselben Thread); Mesh-Jobs laufen mit
 * niedrigster Priorität (unter Spieler-Remeshes und Chunk-Load) auf den Chunk-Workern.
 */
public class LodManager {

    private static final class TimingWindow {
        private final long[] recent = new long[256];
        private long total, count, max;
        private int cursor;

        synchronized void add(long nanos) {
            if (nanos <= 0) return;
            this.total += nanos;
            this.count++;
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
                    this.total / this.count / 1_000_000.0, p95 / 1_000_000.0, this.max / 1_000_000.0);
        }
    }

    /** Fertiges Regionen-Mesh (Worker → Render-Thread). Leere data = Region komplett geclippt.
        sizeRegions = Footprint in 128er-Regionen (1 = normal, 4 = Superregion). */
    public record LodClipSnapshot(int centerMask, int northEdge, int southEdge,
                                  int westEdge, int eastEdge, long boundaryRevision) {
        public LodClipSnapshot(int centerMask, int northEdge, int southEdge,
                               int westEdge, int eastEdge) {
            this(centerMask, northEdge, southEdge, westEdge, eastEdge, 0L);
        }

        static LodClipSnapshot centerOnly(int mask) {
            return new LodClipSnapshot(mask, 0, 0, 0, 0, 0L);
        }

        boolean hasAnyExactTerrain() {
            return (this.centerMask | this.northEdge | this.southEdge
                    | this.westEdge | this.eastEdge) != 0;
        }

        boolean edgeClipped(int face, int chunkAlongEdge) {
            int bits = switch (face) {
                case 2 -> this.northEdge;
                case 3 -> this.southEdge;
                case 4 -> this.westEdge;
                case 5 -> this.eastEdge;
                default -> 0;
            };
            return (bits & (1 << Math.clamp(chunkAlongEdge, 0, 3))) != 0;
        }
    }

    /** Der beim Meshen gueltige Levelvertrag mit den vier Nachbarregionen. */
    public record LodNeighborSnapshot(int northLevel, int southLevel,
                                      int westLevel, int eastLevel) {
        static LodNeighborSnapshot sameLevel(int level) {
            return new LodNeighborSnapshot(level, level, level, level);
        }

        int level(int face) {
            return switch (face) {
                case 2 -> this.northLevel;
                case 3 -> this.southLevel;
                case 4 -> this.westLevel;
                case 5 -> this.eastLevel;
                default -> throw new IllegalArgumentException("Keine horizontale LOD-Seite: " + face);
            };
        }
    }

    public record LodMeshResult(int level, int rx, int rz, int sizeRegions, int epoch, int mask,
                                LodClipSnapshot clipSnapshot, LodNeighborSnapshot neighborSnapshot,
                                int yBase, int[] opaqueData,
                                int[] translucentData, float minY, float maxY) {}

    /* Stand einer hochgeladenen Region: Level + Größe + Settings-Epoche + Chunk-Maske des Meshes */
    private record Current(int level, int sizeRegions, int epoch, LodClipSnapshot clipSnapshot,
                           LodNeighborSnapshot neighborSnapshot) {}

    /* clip = reiner Masken-Diff (Level/Epoche stimmen schon) → höhere Worker-Priorität */
    private record Candidate(long key, int level, LodClipSnapshot clipSnapshot,
                             LodNeighborSnapshot neighborSnapshot, int band, int viewTier,
                             double distanceSq, boolean urgent) {}

    private record CompletedMesh(LodMeshResult result, long completedNanos) {}

    /**
     * Deckel fuer DRINGENDE Kandidaten (Clip-/Handoff-Remeshes) je Tick. Bewusst getrennt vom
     * normalen Budget: {@code submitLodTask(..., clip=true, ...)} reiht direkt ein und laeuft nie
     * ueber die Admission des ChunkManagers, dringende Jobs zaehlen also gar nicht gegen
     * {@code workerCount*4}. Haengte ihre Zulassung am normalen Budget, verhungerte bei
     * gesaettigter LOD-Queue genau der Job, der Doppelgeometrie aufloest oder einen wartenden
     * L0-Unload freigibt.
     */
    private static final int MAX_URGENT_SUBMITS_PER_TICK = 32;

    /** Wie viele dringende Kandidaten dieser Tick einreiht. */
    static int urgentSubmitCount(int urgentCandidates) {
        return Math.min(urgentCandidates, MAX_URGENT_SUBMITS_PER_TICK);
    }

    /**
     * Wie viele NORMALE Kandidaten dieser Tick zusaetzlich einreiht. Bleibt ein dringender
     * Ueberhang liegen (mehr als {@link #MAX_URGENT_SUBMITS_PER_TICK}), ist das Ergebnis 0:
     * normale Jobs wuerden sonst Worker belegen, waehrend Handoffs auf den naechsten Tick warten.
     * Genau 32 dringende sind noch KEIN Ueberhang.
     */
    static int normalSubmitCount(int urgentCandidates, int normalCandidates, int normalBudget) {
        if (urgentCandidates > MAX_URGENT_SUBMITS_PER_TICK) return 0;
        return Math.min(normalCandidates, Math.max(0, normalBudget));
    }

    /* Sichtkegel der Submit-Reihenfolge — gleicher Kegel wie die Chunk-Ladereihenfolge
       (ChunkManager.VIEW_CONE_COS, cos 75°): Regionen im Blickfeld zuerst, sonst ändert ein
       180°-Dreh nichts am sichtbaren LOD-Fortschritt (vorher rein distanzsortiert). */
    private static final double VIEW_CONE_COS = 0.2588;

    /* Hysterese: Recompute erst, wenn der Spieler Regionshälfte (64) + Puffer verlässt. */
    private static final int RECOMPUTE_DISTANCE = 64 + 24;

    private final Logger logger = LogManager.getLogger(LodManager.class.getName());

    private final LodDataSource source;
    private final LodBlockAppearance appearance;
    private final ChunkManager chunkManager;

    /* Eine Mesher-Instanz pro Worker-Thread (wiederverwendete Puffer, wie ChunkMesher) */
    private final ThreadLocal<LodMesher> meshers = ThreadLocal.withInitial(LodMesher::new);

    /* Soll-Zustand: regionKey -> Level. Nur auf dem Tick-/Render-Thread. Boxing-freie
       Long-Maps: der submitPass-Scan läuft jeden Tick über ALLE Einträge (~3400 bei
       lodMax=128, quadratisch wachsend) und lodShowsCell ist der heißeste Reader pro Frame. */
    private final LongIntMap desired = new LongIntMap(4096);
    /* Ist-Zustand der hochgeladenen Regionen (Bookkeeping für Resubmit-Entscheidungen) */
    private final LongObjMap<Current> current = new LongObjMap<>(4096);
    /* Bereits submittete, noch nicht abgeholte Jobs (gegen Doppel-Submits; Long-Set) */
    private final LongIntMap inflight = new LongIntMap(64);
    /* Worker -> Render-Thread */
    private final ConcurrentLinkedQueue<CompletedMesh> results = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> failedJobs = new ConcurrentLinkedQueue<>();
    private final TimingWindow meshSamplingTimes = new TimingWindow();
    private final TimingWindow meshGeometryTimes = new TimingWindow();
    private final TimingWindow resultQueueTimes = new TimingWindow();
    private final TimingWindow uploadTimes = new TimingWindow();
    private final java.util.concurrent.atomic.AtomicLong staleJobs = new java.util.concurrent.atomic.AtomicLong();
    private int telemetryTicks;

    /* Startdiagnose (nur Debug-Log): true, sobald der Ring einmal vollstaendig stand. Schaltet
       zugleich den Telemetrie-Takt von 5 s (Aufbauphase) zurueck auf 30 s (Normalbetrieb). */
    private boolean ringCompleteLogged;
    private int ringCompleteCycle = -1;

    /* Settings-Epoche: rd-/lodMaxDistance-/LOD-Toggle-/AO-Toggle-Änderung entwertet alle
       gebauten Meshes (AO steckt fest im LOD-Mesh, muss also neu gebaut werden) */
    private volatile int epoch = 0;

    private record Schedule(int anchorX, int anchorZ, int epoch, int desiredVersion,
                            LodConfig config, boolean enabled) {}
    private volatile Schedule schedule = new Schedule(0, 0, -1, -1, LodConfig.of(2, 2), false);

    /* Version des desired-Sets: bumpt bei JEDEM recomputeDesired — auch bei reiner
       Anker-Bewegung. epoch ist bewusst KEIN Ersatz (bumpt nur bei settingsChanged)!
       Der ChunkRenderer räumt seine LOD-Meshes nur in Frames auf, in denen sich dieser
       Wert geändert hat, statt jeden Frame alle Regionen gegen desired zu prüfen. */
    private int desiredVersion = 0;

    /* Ring-Konfiguration der aktuellen Epoche — wird den Mesh-Jobs mitgegeben */
    private LodConfig config = LodConfig.of(16, 128);

    /* Anker der Level-Zuordnung: Zentrum der Spieler-Region des letzten Recompute (Blöcke) */
    private int anchorX = Integer.MIN_VALUE, anchorZ;

    /* Zustand der letzten Desired-Berechnung */
    private int lastRenderDistance = -1, lastLodMaxDistance = -1;
    private boolean lastEnabled = true;
    private boolean lastAmbientOcclusion = true;
    private GameSettings.LodQuality lastLodQuality = GameSettings.LodQuality.LOW;

    /* Spieler-Chunk (aktuell) — Zentrum der Masken-Scan-Zone */
    private int pcx, pcz;

    /* Blickrichtung (aktuell) — Basis des Sichtkegel-Scores in submitPass */
    private double viewX, viewZ = -1;

    public LodManager(LodDataSource source, LodBlockAppearance appearance, ChunkManager chunkManager) {
        this.source = source;
        this.appearance = appearance;
        this.chunkManager = chunkManager;
    }

    public static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Einmal pro Tick, nach {@code chunkManager.update()}. */
    public void update(EntityPlayer player) {
        /* Zyklus-Abgleich als ERSTE Anweisung — vor jeder Pruefung von ringCompleteLogged
           (Telemetrie-Takt und reportRingComplete). Laege er dahinter, verhinderte der alte
           Messzustand sein eigenes Zuruecksetzen. */
        int loadCycle = this.chunkManager.loadCycle();
        if (loadCycle != this.ringCompleteCycle) {
            this.ringCompleteCycle = loadCycle;
            this.ringCompleteLogged = false;
            this.telemetryTicks = 0;
        }
        Long failed;
        while ((failed = this.failedJobs.poll()) != null) this.inflight.remove(failed);
        /* Debug-Pause friert auch das LOD ein (Desired-Set + Masken bleiben stehen, fertige
           Ergebnisse lädt der Renderer weiter hoch). */
        if (this.chunkManager.isLoadingPaused()) return;

        GameSettings settings = GameSettings.get();
        boolean enabled = settings.lodEnabled;
        int rd = settings.renderDistance;
        int lodMax = settings.lodMaxDistance;
        boolean ao = settings.ambientOcclusion;
        GameSettings.LodQuality lodQuality = settings.lodQuality;

        this.pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;

        /* Blickrichtung für den Submit-Score (gleiche Yaw-Konvention wie ChunkManager) */
        double yawRad = Math.toRadians(player.yaw);
        this.viewX = Math.sin(yawRad);
        this.viewZ = -Math.cos(yawRad);

        /* Die LOD-Qualitaet MUSS hier mit hinein: AO steckt fest im gebackenen Mesh, ein
           Stufenwechsel muss deshalb alle vorhandenen Meshes entwerten. Fehlt die Bedingung,
           wirkt die Umschaltung nur auf neu gebaute Regionen und der Ring ist gemischt. */
        boolean settingsChanged = enabled != this.lastEnabled || rd != this.lastRenderDistance
                || lodMax != this.lastLodMaxDistance || ao != this.lastAmbientOcclusion
                || lodQuality != this.lastLodQuality;
        if (settingsChanged) {
            this.epoch++; // alle Meshes entwertet (Ringe verschoben)
            this.config = LodConfig.of(rd, lodMax);
        }

        /* Hysterese: Anker erst versetzen, wenn der Spieler deutlich aus der Anker-Region raus ist */
        boolean anchorStale = this.anchorX == Integer.MIN_VALUE
                || Math.max(Math.abs(player.x - this.anchorX), Math.abs(player.z - this.anchorZ)) > RECOMPUTE_DISTANCE;

        if (settingsChanged || anchorStale) {
            this.anchorX = Math.floorDiv((int) Math.floor(player.x), LodMesher.REGION_BLOCKS)
                    * LodMesher.REGION_BLOCKS + LodMesher.REGION_BLOCKS / 2;
            this.anchorZ = Math.floorDiv((int) Math.floor(player.z), LodMesher.REGION_BLOCKS)
                    * LodMesher.REGION_BLOCKS + LodMesher.REGION_BLOCKS / 2;
            this.recomputeDesired(enabled);
            this.lastEnabled = enabled;
            this.lastRenderDistance = rd;
            this.lastLodMaxDistance = lodMax;
            this.lastAmbientOcclusion = ao;
            this.lastLodQuality = lodQuality;
        }

        /* Echtes Terrain zuerst: LOD-Jobs erst einreihen, wenn der Radius einmal komplett stand.
           PRIO_LOD allein reicht dafür NICHT — die Priorität verhindert nur das Verdrängen in der
           Queue; läuft sie kurz leer, greifen freie Worker sofort einen LOD-Job und ziehen ihn
           ohne Präemption durch. Und beim Start sind diese Jobs teuer: ohne residente Chunks fällt
           die PersistentLodDataSource je Quellchunk auf Disk-Cache, Savegame oder Generator
           zurück — auf jedem Level, nicht nur in den Fernringen. */
        if (!this.desired.isEmpty() && this.chunkManager.isInitialLoadComplete()) this.submitPass();
        /* Waehrend des Ring-Aufbaus alle 100 Ticks (5 s) statt alle 600 (30 s): ein Aufbau von
           wenigen Sekunden ist im 30-s-Takt nicht aufloesbar, im Normalbetrieb waere er unnoetig
           gespraechig. */
        if (++this.telemetryTicks >= (this.ringCompleteLogged ? 600 : 100)) {
            this.telemetryTicks = 0;
            if (this.source instanceof PersistentLodDataSource persistent) {
                this.logger.debug(persistent.debugStats() + ", " + this.chunkManager.lodWorkerStats()
                        + ", " + this.residencyStats()
                        + ", Mesh-Sampling="
                        + this.meshSamplingTimes.text() + ", Mesh-Geometrie="
                        + this.meshGeometryTimes.text() + ", Ergebnis-Queue="
                        + this.resultQueueTimes.text() + ", Upload=" + this.uploadTimes.text());
            }
        }
    }

    /** Baut den Soll-Zustand neu: alle Regionen bis zum äußersten Ring, Level pro Region. */
    private void recomputeDesired(boolean enabled) {
        this.desiredVersion++;
        this.desired.clear();
        if (!enabled) {
            this.schedule = new Schedule(this.anchorX, this.anchorZ, this.epoch,
                    this.desiredVersion, this.config, false);
            this.chunkManager.updateLodScheduleVersion(this.desiredVersion);
            this.current.clear(); // Renderer räumt die Meshes ab (isDesiredKey == false)
            return;
        }

        double outer = this.config.outerRadiusBlocks();
        int prx = Math.floorDiv(this.anchorX, LodMesher.REGION_BLOCKS);
        int prz = Math.floorDiv(this.anchorZ, LodMesher.REGION_BLOCKS);
        int radius = (int) Math.ceil((outer + LodMesher.HALF_DIAG) / LodMesher.REGION_BLOCKS);

        int[] counts = new int[this.config.maxEffectiveLevel() + 1];
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int rx = prx + dx, rz = prz + dz;
                double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - this.anchorX;
                double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - this.anchorZ;
                double d = Math.sqrt(cx * cx + cz * cz);
                if (d - LodMesher.HALF_DIAG >= outer) continue; // ganz außerhalb
                /* Innen wird NICHT ausgeschlossen — dort clippt die Chunk-Maske zellgenau
                   (und füllt Lücken, solange Chunks noch laden).

                   KEINEN Radius-Ausschluss für den Innenbereich einbauen (einmal gebaut, sofort
                   wieder ausgebaut): Er misst vom ANKER, der dem Spieler per Hysterese bis
                   RECOMPUTE_DISTANCE hinterherhinkt, während der Chunk-Unload vom SPIELER misst
                   ((rd+2)*32). Zellen einer so ausgeschlossenen Region landen dadurch jenseits des
                   Unload-Radius → Chunk weg, LOD nie da → Ring aus Löchern beim Fliegen. Zusätzlich
                   schaltet er still das Unload-Gate ab (coversChunk liefert für nicht gewünschte
                   Regionen true). Nötig ist er ohnehin nicht: sind die 16 Chunks geladen, ist die
                   Maske 0xFFFF und LodMesher.mesh steigt mit einem LEEREN Mesh aus — LOD unter dem
                   Spieler kostet nichts. Gegen LOD-Geflacker beim Weltstart wirkt das Lade-Gate
                   (ChunkManager.isInitialLoadComplete), nicht die Ring-Geometrie. */
                int level = this.config.levelAt(d);
                this.desired.put(key(rx, rz), level);
                counts[level]++;
            }
        }

        /* Bookkeeping nicht mehr gewünschter Regionen aufräumen (Meshes räumt der Renderer ab) */
        this.current.removeIf((k, v) -> !this.desired.containsKey(k));
        this.schedule = new Schedule(this.anchorX, this.anchorZ, this.epoch,
                this.desiredVersion, this.config, true);
        this.chunkManager.updateLodScheduleVersion(this.desiredVersion);

        StringBuilder sb = new StringBuilder("LOD-Regionen: ");
        for (int l = 1; l < counts.length; l++) {
            if (counts[l] > 0) sb.append("L").append(l).append("=").append(counts[l]).append(" ");
        }
        this.logger.debug(sb.append("(gesamt ").append(this.desired.size()).append(")").toString());
    }

    /**
     * 16-Bit-Maske der Region: Bit gesetzt = Chunk zeigt gerade WIRKLICH echtes Terrain.
     * READY allein reicht nicht (heißt nur "Batches eingereiht") — erst wenn der Renderer
     * alle Sections angewendet hat, darf das LOD weichen, sonst flackern Löcher auf,
     * bevor der echte Mesh sichtbar ist.
     */
    private int computeMask(int rx, int rz) {
        int baseCx = rx * 4, baseCz = rz * 4;
        int mask = 0;
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                Chunk chunk = this.chunkManager.getChunk(baseCx + dx, baseCz + dz);
                /* pendingUnload zählt als abwesend: die Region un-clippt die Zelle, BEVOR der
                   Chunk wirklich verschwindet (symmetrisches Gegenstück zum Upload-Gate). */
                if (chunk != null && chunk.status == ChunkStatus.READY && chunk.isFullyUploaded()
                        && !chunk.pendingUnload) {
                    mask |= 1 << (dz * 4 + dx);
                }
            }
        }
        return mask;
    }

    /**
     * Atomarer Sicht-Snapshot der eigenen 4x4 Chunks plus je einer Chunk-Reihe an den vier
     * Regionskanten. Die Randbits sind noetig, weil eine L0/LOD-Kante exakt auf einer
     * 128-Block-Regionsgrenze liegen kann und dann in der lokalen 16-Bit-Maske unsichtbar waere.
     */
    private LodClipSnapshot computeClipSnapshot(int rx, int rz) {
        int baseCx = rx * 4, baseCz = rz * 4;
        int center = this.computeMask(rx, rz);
        int north = 0, south = 0, west = 0, east = 0;
        for (int i = 0; i < 4; i++) {
            if (this.chunkShowsTerrain(baseCx + i, baseCz - 1)) north |= 1 << i;
            if (this.chunkShowsTerrain(baseCx + i, baseCz + 4)) south |= 1 << i;
            if (this.chunkShowsTerrain(baseCx - 1, baseCz + i)) west |= 1 << i;
            if (this.chunkShowsTerrain(baseCx + 4, baseCz + i)) east |= 1 << i;
        }
        long boundaryRevision = 0x9E3779B97F4A7C15L;
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                boundaryRevision = this.mixBoundaryRevision(boundaryRevision,
                        baseCx + dx, baseCz + dz);
            }
        }
        for (int i = 0; i < 4; i++) {
            boundaryRevision = this.mixBoundaryRevision(boundaryRevision,
                    baseCx + i, baseCz - 1);
            boundaryRevision = this.mixBoundaryRevision(boundaryRevision,
                    baseCx + i, baseCz + 4);
            boundaryRevision = this.mixBoundaryRevision(boundaryRevision,
                    baseCx - 1, baseCz + i);
            boundaryRevision = this.mixBoundaryRevision(boundaryRevision,
                    baseCx + 4, baseCz + i);
        }
        if ((center | north | south | west | east) == 0) boundaryRevision = 0L;
        return new LodClipSnapshot(center, north, south, west, east, boundaryRevision);
    }

    private long mixBoundaryRevision(long hash, int cx, int cz) {
        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        if (chunk == null || chunk.status != ChunkStatus.READY || !chunk.isFullyUploaded()
                || chunk.pendingUnload) return hash;
        long value = Chunk.key(cx, cz) ^ Long.rotateLeft(chunk.boundaryMeshRevision(), 29);
        return Long.rotateLeft(hash ^ value, 17) * 0xD6E8FEB86659FD93L;
    }

    /**
     * Liest den Nahtvertrag aus demselben Desired-Snapshot, der auch die Jobs bestimmt.
     * Eine Region ausserhalb des Rings wird absichtlich als gleichaufloesend behandelt;
     * dort endet die Darstellung im Fog und es gibt keinen zweiten LOD-Partner.
     */
    private LodNeighborSnapshot computeNeighborSnapshot(int rx, int rz, int ownLevel) {
        return neighborSnapshot(this.desired, rx, rz, ownLevel);
    }

    static LodNeighborSnapshot neighborSnapshot(LongIntMap desired, int rx, int rz, int ownLevel) {
        return new LodNeighborSnapshot(
                desired.getOrDefault(key(rx, rz - 1), ownLevel),
                desired.getOrDefault(key(rx, rz + 1), ownLevel),
                desired.getOrDefault(key(rx - 1, rz), ownLevel),
                desired.getOrDefault(key(rx + 1, rz), ownLevel));
    }

    static boolean topologyChanged(LodNeighborSnapshot current,
                                   LodNeighborSnapshot desired) {
        return !desired.equals(current);
    }

    static boolean meshContractMatches(LodClipSnapshot resultClip,
                                       LodNeighborSnapshot resultNeighbors,
                                       LodClipSnapshot currentClip,
                                       LodNeighborSnapshot currentNeighbors) {
        return resultClip.equals(currentClip) && resultNeighbors.equals(currentNeighbors);
    }

    private boolean chunkShowsTerrain(int cx, int cz) {
        Chunk chunk = this.chunkManager.getChunk(cx, cz);
        return chunk != null && chunk.status == ChunkStatus.READY && chunk.isFullyUploaded()
                && !chunk.pendingUnload;
    }

    /** Eine fehlende Region, die gerade einen L0-Unload blockiert, ist ebenfalls ein Handoff-Job. */
    private boolean hasPendingUnload(int rx, int rz) {
        int baseCx = rx * 4, baseCz = rz * 4;
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                Chunk chunk = this.chunkManager.getChunk(baseCx + dx, baseCz + dz);
                if (chunk != null && chunk.pendingUnload) return true;
            }
        }
        return false;
    }

    /** Submittet fehlende/veraltete Regionen nah-zuerst, budgetiert pro Tick. */
    private void submitPass() {
        /* Regionen in dieser Distanz können geladene Chunks enthalten → Masken-Scan nötig */
        double nearReal = (this.config.renderDistance() + 2) * 32.0 + LodMesher.HALF_DIAG;
        double playerX = this.pcx * 32.0 + 16.0;
        double playerZ = this.pcz * 32.0 + 16.0;

        List<Candidate> candidates = null;
        int urgentCandidates = 0;
        /* Cursor-Iteration statt entrySet: kein Long-/Integer-Unboxing je Eintrag. */
        for (int idx = 0, n = this.desired.tableSize(); idx < n; idx++) {
            if (!this.desired.usedAt(idx)) continue;
            long key = this.desired.keyAt(idx);
            int level = this.desired.valueAt(idx);
            if (this.inflight.containsKey(key)) continue;

            int rx = (int) (key >> 32), rz = (int) key;
            double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - playerX;
            double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - playerZ;
            double distSq = cx * cx + cz * cz;

            Current c = this.current.get(key);
            LodNeighborSnapshot neighborSnapshot = this.computeNeighborSnapshot(rx, rz, level);
            boolean baseNeeds = c == null || c.level != level || c.epoch != this.epoch;
            boolean topologyNeeds = c != null
                    && topologyChanged(c.neighborSnapshot, neighborSnapshot);
            boolean needs = baseNeeds || topologyNeeds;
            boolean validVisual = !baseNeeds;
            boolean urgent = topologyNeeds;
            LodClipSnapshot clipSnapshot = null; // noch nicht berechnet
            if (needs && this.chunkManager.hasPendingUnloads() && this.hasPendingUnload(rx, rz)) {
                /* Auch ein kompletter Erst-/Epochen-Build ist dringend, wenn sichtbares L0
                   auf genau diese Region als atomaren Ersatz wartet. */
                urgent = true;
                clipSnapshot = this.computeClipSnapshot(rx, rz);
            }
            if (validVisual && (c.clipSnapshot.hasAnyExactTerrain()
                    || distSq < nearReal * nearReal)) {
                /* Masken-Diff: Chunk fertig geladen oder entladen → Region remeshen.
                   Ein gesetztes Zentrum- oder Randbit fängt den Unload-Fall auch außerhalb
                   der Nahzone.
                   Diese Clip-Remeshes laufen mit erhöhter Priorität (PRIO_LOD_CLIP) —
                   sonst steht die alte LOD-Geometrie sekundenlang über frisch
                   erschienenen L0-Chunks (hinter der vollen Lade-Queue). */
                clipSnapshot = this.computeClipSnapshot(rx, rz);
                boolean clipChanged = !clipSnapshot.equals(c.clipSnapshot);
                needs |= clipChanged;
                urgent |= clipChanged;
            }
            if (!needs) continue;
            int band = distanceBand(cx, cz);

            /* Distanzband ist der primaere LOD-Schluessel: nahe L1-Arbeit ueberholt damit
               ferne L2/L3-Arbeit, ohne alle Level global zu serialisieren. Innerhalb des
               Bandes entscheiden Level, Sichtkegel und exakte Distanz. */
            double dist = Math.sqrt(distSq);
            double cos = dist > 0 ? (cx * this.viewX + cz * this.viewZ) / dist : 1.0;
            int viewTier = cos >= VIEW_CONE_COS ? 0 : 1;

            if (candidates == null) candidates = new ArrayList<>();
            if (urgent) urgentCandidates++;
            candidates.add(new Candidate(key, level, clipSnapshot, neighborSnapshot,
                    band, viewTier, distSq, urgent));
        }
        if (candidates == null) {
            this.reportRingComplete();
            return;
        }

        candidates.sort(Comparator.comparingInt((Candidate candidate) -> candidate.urgent ? 0 : 1)
                .thenComparingInt(Candidate::band)
                .thenComparingInt(Candidate::level)
                .thenComparingInt(Candidate::viewTier)
                .thenComparingDouble(Candidate::distanceSq));
        /* ZWEI getrennte Budgets, weil dringende und normale Jobs auf verschiedenen Wegen in den
           Pool gehen (s. MAX_URGENT_SUBMITS_PER_TICK).

           Normale Jobs deckelt der ChunkManager selbst (workerCount*4 minus laufende/wartende).
           Frueher stand hier stattdessen ein fester Deckel von 32/Tick fuer ALLES; der war auf
           Maschinen mit vielen Kernen die eigentliche Bremse des Ring-Aufbaus. Gemessen mit
           30 Workern und warmem Cache (Seed 187): 30 Worker aktiv, nur 2 Jobs in der
           Warteschlange, Inflight konstant 32 — der Ring brauchte allein an Nachschub-Latenz
           3365/32 = 105 Ticks = 5,3 s, voellig unabhaengig von der Rechenleistung. Ohne den
           festen Deckel faellt derselbe Ring von 6,0 s auf 2,0 s nach dem Lade-Fixpunkt.

           Die Sortierung stellt die Dringenden an den Anfang, die beiden Budgets waehlen daher
           zwei zusammenhaengende Bereiche aus. */
        int urgentTake = urgentSubmitCount(urgentCandidates);
        int normalTake = normalSubmitCount(urgentCandidates, candidates.size() - urgentCandidates,
                this.chunkManager.normalLodSubmissionBudget());
        for (int n = 0, total = urgentTake + normalTake; n < total; n++) {
            Candidate cand = candidates.get(n < urgentTake ? n : urgentCandidates + n - urgentTake);
            this.inflight.put(cand.key, 1);

            int rx = (int) (cand.key >> 32), rz = (int) cand.key;
            int level = cand.level, jobEpoch = this.epoch;
            int jobDesiredVersion = this.desiredVersion;
            LodClipSnapshot clipSnapshot = cand.clipSnapshot != null
                    ? cand.clipSnapshot : this.computeClipSnapshot(rx, rz);
            LodNeighborSnapshot neighborSnapshot = cand.neighborSnapshot;
            int jobAx = this.anchorX, jobAz = this.anchorZ;
            LodConfig jobConfig = this.config;
            ChunkManager.LodPriority priority = new ChunkManager.LodPriority(jobDesiredVersion,
                    cand.band, level, cand.viewTier, cand.distanceSq);
            boolean accepted = this.chunkManager.submitLodTask(() -> {
                try {
                    if (!this.jobStillDesired(rx, rz, level, jobEpoch, jobDesiredVersion)) {
                        this.staleJobs.incrementAndGet();
                        this.failedJobs.add(cand.key);
                        return;
                    }
                    LodMesher mesher = this.meshers.get();
                    LodMeshResult result = mesher.mesh(this.source, this.appearance, jobConfig,
                            level, 1, rx, rz, jobEpoch, clipSnapshot, neighborSnapshot,
                            jobAx, jobAz);
                    this.results.add(new CompletedMesh(result, System.nanoTime()));
                    this.meshSamplingTimes.add(mesher.lastSamplingNanos());
                    this.meshGeometryTimes.add(mesher.lastGeometryNanos());
                } catch (Throwable error) {
                    this.logger.warning("LOD-Mesh fuer Region (" + rx + ", " + rz + ") fehlgeschlagen", error);
                    this.failedJobs.add(cand.key);
                }
            }, cand.urgent, priority, () -> this.inflight.remove(cand.key));
            if (!accepted) this.inflight.remove(cand.key);
        }
    }

    /**
     * Startdiagnose: meldet einmalig, wann der LOD-Ring erstmals vollstaendig stand. Der Test ist
     * gratis, weil {@code submitPass} ohnehin alle gewuenschten Regionen durchlaeuft: keine
     * Kandidaten heisst, jede Region hat ein akzeptiertes Mesh mit passendem Level und passender
     * Epoche — und ein noch nicht abgeholtes Ergebnis haengt bis dahin im In-Flight-Set.
     */
    private void reportRingComplete() {
        if (this.ringCompleteLogged || this.inflight.size() != 0) return;
        this.ringCompleteLogged = true;
        this.logger.debug("LOD-Ring vollstaendig nach "
                + ((System.nanoTime() - this.chunkManager.loadStartNanos()) / 1_000_000L)
                + " ms (ab Weltbetreten), " + this.desired.size() + " Regionen");
    }

    private String residencyStats() {
        int exact = 0;
        for (int i = 0, n = this.current.tableSize(); i < n; i++) {
            Current value = this.current.valueAt(i);
            if (value == null) continue;
            if (value.epoch != this.epoch
                    || this.desired.getOrDefault(this.current.keyAt(i), -1) != value.level) continue;
            exact++;
        }
        int missing = Math.max(0, this.desired.size() - exact);
        return "LOD-Resident: Exact=" + exact
                + ", Fehlend=" + missing + ", Gewuenscht=" + this.desired.size()
                + ", Inflight=" + this.inflight.size()
                + ", stale=" + this.staleJobs.get();
    }

    private boolean jobStillDesired(int rx, int rz, int level, int jobEpoch, int jobDesiredVersion) {
        if (this.epoch != jobEpoch) return false;
        Schedule snapshot = this.schedule;
        if (!snapshot.enabled || snapshot.epoch != jobEpoch
                || snapshot.desiredVersion != jobDesiredVersion) return false;
        double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - snapshot.anchorX;
        double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - snapshot.anchorZ;
        double distance = Math.sqrt(cx * cx + cz * cz);
        return distance - LodMesher.HALF_DIAG < snapshot.config.outerRadiusBlocks()
                && snapshot.config.levelAt(distance) == level;
    }

    /* ------------------- API für den ChunkRenderer (Render-Thread) ------------------- */

    static int distanceBand(double dx, double dz) {
        return (int) (Math.sqrt(dx * dx + dz * dz) / LodMesher.REGION_BLOCKS);
    }

    /** Nächstes fertiges Mesh oder null. Entfernt den Job aus dem In-Flight-Set. */
    public LodMeshResult pollResult() {
        CompletedMesh completed = this.results.poll();
        if (completed == null) return null;
        LodMeshResult result = completed.result;
        this.resultQueueTimes.add(System.nanoTime() - completed.completedNanos);
        this.inflight.remove(key(result.rx(), result.rz()));
        return result;
    }

    /** Render-Thread-Telemetrie fuer den Arena-Upload eines akzeptierten Ergebnisses. */
    public void recordUploadTime(long nanos) {
        this.uploadTimes.add(nanos);
    }

    /**
     * true, wenn das Ergebnis noch gewünscht ist — dann als aktueller Stand registriert.
     * false = verwerfen (Upload↔Unload-Race, sonst Arena-Leak).
     */
    public boolean acceptResult(LodMeshResult result) {
        long key = key(result.rx(), result.rz());
        int want = this.desired.getOrDefault(key, -1); // -1 = nicht gewünscht (Level sind >= 1)
        if (want != result.level() || result.epoch() != this.epoch) return false;
        /* Während der Worker mesht, kann der Spieler weitere Chunkgrenzen überqueren.
           Einen veralteten Maskenstand kurz hochzuladen würde L0 festhalten oder Doppelbilder
           erzeugen und danach noch einen zweiten Remesh brauchen. Nur der aktuelle Stand darf
           den atomaren Handoff vollziehen; der Tick submittet den Ersatz unmittelbar neu. */
        if (!meshContractMatches(result.clipSnapshot(), result.neighborSnapshot(),
                this.computeClipSnapshot(result.rx(), result.rz()),
                this.computeNeighborSnapshot(result.rx(), result.rz(), result.level()))) return false;
        this.current.put(key, new Current(result.level(), result.sizeRegions(), result.epoch(),
                result.clipSnapshot(), result.neighborSnapshot()));
        return true;
    }

    /** true, solange die Region gewünscht ist — der Renderer räumt Meshes ab, sobald false. */
    public boolean isDesiredKey(long key) {
        return this.desired.containsKey(key);
    }

    /** Version des desired-Sets (s. Feld-Kommentar) — Gate für den Cleanup-Walk im Renderer. */
    public int getDesiredVersion() {
        return desiredVersion;
    }

    /**
     * true, wenn der Chunk ohne sichtbares Loch entladen werden kann: das hochgeladene
     * LOD-Mesh zeigt seine Zelle bereits (Bit ungesetzt = ungeclippt) oder dort ist kein
     * LOD gewünscht (Region außerhalb / LOD aus → desired leer). Die Epoche wird bewusst
     * ignoriert — current.mask beschreibt immer das Mesh auf dem Schirm, auch über
     * Epoch-Wechsel hinweg (stale Ergebnisse werden nie hochgeladen).
     */
    public boolean coversChunk(int cx, int cz) {
        long key = key(Math.floorDiv(cx, 4), Math.floorDiv(cz, 4));
        if (!this.desired.containsKey(key)) return true;
        Current c = this.current.get(key);
        if (c == null) return false; // erster Upload der Region steht noch aus
        int bit = Math.floorMod(cz, 4) * 4 + Math.floorMod(cx, 4);
        return (c.clipSnapshot.centerMask & (1 << bit)) == 0;
    }

    /**
     * Sicht-Gate für den Renderer: true, wenn ein HOCHGELADENES LOD-Mesh die Zelle (cx,cz)
     * gerade zeigt (Bit ungeclippt) — dann werden die Section-Meshes dieses Chunks NICHT
     * gezeichnet. Sobald das geclippte LOD-Mesh übernommen wird (acceptResult aktualisiert
     * current.mask, läuft im Frame VOR dem Cull-Pass), erscheint der Chunk im SELBEN Frame:
     * atomarer Swap statt Doppelbild (Laden) bzw. Doppelframe (Entladen).
     *
     * <p>Bewusst NICHT {@link #coversChunk} verwenden: dessen "!desired → true"-Zweig würde
     * Chunks ohne LOD fälschlich verstecken (Loch). current ist auf desired gepruned, daher
     * reicht der eine Lookup; ist kein Mesh hochgeladen (c == null), gibt es nichts, das den
     * Chunk verdecken könnte → zeichnen.
     */
    public boolean lodShowsCell(int cx, int cz) {
        Current c = this.current.get(key(Math.floorDiv(cx, 4), Math.floorDiv(cz, 4)));
        if (c == null) return false;
        int bit = Math.floorMod(cz, 4) * 4 + Math.floorMod(cx, 4);
        return (c.clipSnapshot.centerMask & (1 << bit)) == 0;
    }

    /**
     * Block-State der Darstellung, die an dieser Position tatsaechlich auf dem Schirm liegt.
     * {@code -1} bedeutet, dass dort kein hochgeladenes LOD-Mesh sichtbar ist und der Aufrufer
     * auf die echten Chunkdaten zurueckfallen muss. Diese API ist ausschliesslich fuer visuelle
     * Samples (Kamerafluid/Licht) bestimmt; Kollisionen und Interaktionen bleiben bei L0.
     */
    public int visibleStateAt(int x, int y, int z) {
        Current visible = this.visibleCurrentAt(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (visible == null) return -1;
        if (y < 0 || y >= Chunk.HEIGHT) return Blocks.AIR;
        int size = 1 << visible.level;
        int cellX = Math.floorDiv(x, size) * size;
        int cellZ = Math.floorDiv(z, size) * size;
        return stateAt(this.source.sampleColumn(cellX, cellZ, size), y);
    }

    /**
     * Himmelslicht der sichtbaren LOD-Spalte oder {@code -1}, wenn an der Position L0 gilt.
     * Die Wasserdaempfung ist dieselbe wie beim LOD-Mesh, damit Hand, Spieler und Kamera nicht
     * mit dem Volllicht-Fallback eines noch ungeladenen Chunks beleuchtet werden.
     */
    public int visibleSkyLightAt(int x, int y, int z) {
        Current visible = this.visibleCurrentAt(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (visible == null) return -1;
        if (y >= Chunk.HEIGHT) return 15;
        if (y < 0) return 0;
        int size = 1 << visible.level;
        int cellX = Math.floorDiv(x, size) * size;
        int cellZ = Math.floorDiv(z, size) * size;
        return skyLightAt(this.source.sampleColumn(cellX, cellZ, size), y, this.appearance);
    }

    private Current visibleCurrentAt(int cx, int cz) {
        Current visible = this.current.get(key(Math.floorDiv(cx, 4), Math.floorDiv(cz, 4)));
        if (visible == null) return null;
        int bit = Math.floorMod(cz, 4) * 4 + Math.floorMod(cx, 4);
        return (visible.clipSnapshot.centerMask & (1 << bit)) == 0 ? visible : null;
    }

    static int stateAt(LodColumn column, int y) {
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (y >= LodColumn.minY(interval) && y < LodColumn.maxY(interval)) {
                return LodColumn.state(interval);
            }
        }
        return Blocks.AIR;
    }

    static int skyLightAt(LodColumn column, float y, LodBlockAppearance appearance) {
        int depth = 0;
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (LodColumn.maxY(interval) <= y
                    || !appearance.attenuatesSkyLight(LodColumn.state(interval))) continue;
            depth += Math.max(0, LodColumn.maxY(interval)
                    - Math.max((int) Math.floor(y), LodColumn.minY(interval)));
        }
        return Math.clamp(15 - depth, 0, 15);
    }
}
