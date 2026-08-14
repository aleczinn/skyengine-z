package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.entity.EntityPlayer;
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
import java.util.concurrent.atomic.AtomicInteger;

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
    public record LodMeshResult(int level, int rx, int rz, int sizeRegions, int epoch, int mask, int yBase,
                                int[] opaqueData, int[] translucentData, float minY, float maxY) {}

    /* Stand einer hochgeladenen Region: Level + Größe + Settings-Epoche + Chunk-Maske des Meshes */
    private record Current(int level, int sizeRegions, int epoch, int mask) {}

    /* clip = reiner Masken-Diff (Level/Epoche stimmen schon) → höhere Worker-Priorität */
    private record Candidate(long key, int level, int mask, double score, boolean clip) {}

    /* Deckelt die Executor-Queue — Jobs sind billig (nur Source-Samples), aber nah-zuerst. */
    private static final int MAX_SUBMITS_PER_TICK = 32;
    private static final int MAX_NORMAL_BUILDS = Runtime.getRuntime().availableProcessors() < 8 ? 1 : 2;

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
    private final ConcurrentLinkedQueue<LodMeshResult> results = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> failedJobs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger runningNormalBuilds = new AtomicInteger();
    private final TimingWindow meshSamplingTimes = new TimingWindow();
    private final TimingWindow meshGeometryTimes = new TimingWindow();
    private int telemetryTicks;

    /* Settings-Epoche: rd-/lodMaxDistance-/LOD-Toggle-/AO-Toggle-Änderung entwertet alle
       gebauten Meshes (AO steckt fest im LOD-Mesh, muss also neu gebaut werden) */
    private int epoch = 0;

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
    private boolean lastGrassOverlay = true; // TEMP/Debug: LodMesher.EMIT_GRASS_OVERLAY

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
        boolean overlay = LodMesher.EMIT_GRASS_OVERLAY; // TEMP/Debug: remesht bei Wechsel

        this.pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;

        /* Blickrichtung für den Submit-Score (gleiche Yaw-Konvention wie ChunkManager) */
        double yawRad = Math.toRadians(player.yaw);
        this.viewX = Math.sin(yawRad);
        this.viewZ = -Math.cos(yawRad);

        boolean settingsChanged = enabled != this.lastEnabled || rd != this.lastRenderDistance
                || lodMax != this.lastLodMaxDistance || ao != this.lastAmbientOcclusion
                || overlay != this.lastGrassOverlay;
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
            this.lastGrassOverlay = overlay;
        }

        /* Echtes Terrain zuerst: LOD-Jobs erst einreihen, wenn der Radius einmal komplett stand.
           PRIO_LOD allein reicht dafür NICHT — die Priorität verhindert nur das Verdrängen in der
           Queue; läuft sie kurz leer, greifen freie Worker sofort einen LOD-Job und ziehen ihn
           ohne Präemption durch. Und beim Start sind diese Jobs teuer: ohne geladene Chunks fällt
           die WorldLodDataSource pro Zelle auf den Generator-Noise zurück. */
        if (!this.desired.isEmpty() && this.chunkManager.isInitialLoadComplete()) this.submitPass();
        if (++this.telemetryTicks >= 600) {
            this.telemetryTicks = 0;
            if (this.source instanceof PersistentLodDataSource persistent) {
                this.logger.debug(persistent.debugStats() + ", laufende normale Jobs="
                        + this.runningNormalBuilds.get() + ", Mesh-Sampling="
                        + this.meshSamplingTimes.text() + ", Mesh-Geometrie="
                        + this.meshGeometryTimes.text());
            }
        }
    }

    /** Baut den Soll-Zustand neu: alle Regionen bis zum äußersten Ring, Level pro Region. */
    private void recomputeDesired(boolean enabled) {
        this.desiredVersion++;
        this.desired.clear();
        if (!enabled) {
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

    /** Submittet fehlende/veraltete Regionen nah-zuerst, budgetiert pro Tick. */
    private void submitPass() {
        /* Regionen in dieser Distanz können geladene Chunks enthalten → Masken-Scan nötig */
        double nearReal = (this.config.renderDistance() + 2) * 32.0 + LodMesher.HALF_DIAG;
        double playerX = this.pcx * 32.0 + 16.0;
        double playerZ = this.pcz * 32.0 + 16.0;

        /* Das kleinste noch unvollständige Distanzband bleibt die Frontier. Auch bereits
           laufende Jobs zählen als unvollständig; dadurch entstehen keine fernen Inseln. */
        int frontierBand = Integer.MAX_VALUE;
        for (int idx = 0, n = this.desired.tableSize(); idx < n; idx++) {
            if (!this.desired.usedAt(idx)) continue;
            long key = this.desired.keyAt(idx);
            Current c = this.current.get(key);
            int level = this.desired.valueAt(idx);
            if (c != null && c.level == level && c.epoch == this.epoch) continue;
            int rx = (int) (key >> 32), rz = (int) key;
            double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - playerX;
            double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - playerZ;
            frontierBand = Math.min(frontierBand, distanceBand(cx, cz));
        }

        List<Candidate> candidates = null;
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
            boolean needs = c == null || c.level != level || c.epoch != this.epoch;
            boolean clip = false;
            int mask = Integer.MIN_VALUE; // noch nicht berechnet
            if (!needs && (c.mask != 0 || distSq < nearReal * nearReal)) {
                /* Masken-Diff: Chunk fertig geladen oder entladen → Region remeshen.
                   c.mask != 0 fängt den Unload-Fall auch außerhalb der Nahzone.
                   Diese Clip-Remeshes laufen mit erhöhter Priorität (PRIO_LOD_CLIP) —
                   sonst steht die alte LOD-Geometrie sekundenlang über frisch
                   erschienenen L0-Chunks (hinter der vollen Lade-Queue). */
                mask = this.computeMask(rx, rz);
                needs = clip = mask != c.mask;
            }
            if (!needs) continue;
            if (!clip && distanceBand(cx, cz) != frontierBand) continue;

            /* Zwei-Stufen-Score wie die Chunk-Ladereihenfolge: Sichtkegel zuerst, innerhalb
               einer Stufe nach Distanz. Bias = outerRadius > jede Stufe-1-Distanz. Ändert NUR
               die Reihenfolge — welche Regionen mit welchem Level gebaut werden, bleibt
               identisch (Anker-/Level-Formel und damit der Determinismus unangetastet). */
            double dist = Math.sqrt(distSq);
            double cos = dist > 0 ? (cx * this.viewX + cz * this.viewZ) / dist : 1.0;
            double score = (cos >= VIEW_CONE_COS ? 0 : LodMesher.REGION_BLOCKS) + dist;

            if (candidates == null) candidates = new ArrayList<>();
            candidates.add(new Candidate(key, level, mask, score, clip));
        }
        if (candidates == null) return;

        candidates.sort(Comparator.comparingInt((Candidate candidate) -> candidate.clip ? 0 : 1)
                .thenComparingDouble(Candidate::score));
        int submitted = 0;
        for (int i = 0; i < candidates.size() && submitted < MAX_SUBMITS_PER_TICK; i++) {
            Candidate cand = candidates.get(i);
            if (!cand.clip && this.runningNormalBuilds.get() >= MAX_NORMAL_BUILDS) continue;
            if (!cand.clip) this.runningNormalBuilds.incrementAndGet();
            this.inflight.put(cand.key, 1);

            int rx = (int) (cand.key >> 32), rz = (int) cand.key;
            int level = cand.level, jobEpoch = this.epoch;
            int mask = cand.mask != Integer.MIN_VALUE ? cand.mask : this.computeMask(rx, rz);
            int jobAx = this.anchorX, jobAz = this.anchorZ;
            LodConfig jobConfig = this.config;
            this.chunkManager.submitLodTask(() -> {
                try {
                    LodMesher mesher = this.meshers.get();
                    this.results.add(mesher.mesh(this.source, this.appearance, jobConfig,
                            level, 1, rx, rz, jobEpoch, mask, jobAx, jobAz));
                    this.meshSamplingTimes.add(mesher.lastSamplingNanos());
                    this.meshGeometryTimes.add(mesher.lastGeometryNanos());
                } catch (Throwable error) {
                    this.logger.warning("LOD-Mesh fuer Region (" + rx + ", " + rz + ") fehlgeschlagen", error);
                    this.failedJobs.add(cand.key);
                } finally {
                    if (!cand.clip) this.runningNormalBuilds.decrementAndGet();
                }
            }, cand.clip);
            submitted++;
        }
    }

    /* ------------------- API für den ChunkRenderer (Render-Thread) ------------------- */

    static int distanceBand(double dx, double dz) {
        return (int) (Math.sqrt(dx * dx + dz * dz) / LodMesher.REGION_BLOCKS);
    }

    /** Nächstes fertiges Mesh oder null. Entfernt den Job aus dem In-Flight-Set. */
    public LodMeshResult pollResult() {
        LodMeshResult result = this.results.poll();
        if (result != null) this.inflight.remove(key(result.rx(), result.rz()));
        return result;
    }

    /**
     * true, wenn das Ergebnis noch gewünscht ist — dann als aktueller Stand registriert.
     * false = verwerfen (Upload↔Unload-Race, sonst Arena-Leak).
     */
    public boolean acceptResult(LodMeshResult result) {
        long key = key(result.rx(), result.rz());
        int want = this.desired.getOrDefault(key, -1); // -1 = nicht gewünscht (Level sind >= 1)
        if (want != result.level() || result.epoch() != this.epoch) return false;
        this.current.put(key, new Current(result.level(), result.sizeRegions(), result.epoch(), result.mask()));
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
        return (c.mask & (1 << bit)) == 0;
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
        return (c.mask & (1 << bit)) == 0;
    }
}
