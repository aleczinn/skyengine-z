package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Fertiges Regionen-Mesh (Worker → Render-Thread). Leere data = Region komplett geclippt. */
    public record LodMeshResult(int level, int rx, int rz, int epoch, int mask, int yBase,
                                int[] opaqueData, int[] translucentData, float minY, float maxY) {}

    /* Stand einer hochgeladenen Region: Level + Settings-Epoche + Chunk-Maske des Meshes */
    private record Current(int level, int epoch, int mask) {}

    private record Candidate(long key, int level, int mask, double distSq) {}

    /* Deckelt die Executor-Queue — Jobs sind billig (nur Source-Samples), aber nah-zuerst. */
    private static final int MAX_SUBMITS_PER_TICK = 32;

    /* Hysterese: Recompute erst, wenn der Spieler Regionshälfte (64) + Puffer verlässt. */
    private static final int RECOMPUTE_DISTANCE = 64 + 24;

    private final Logger logger = LogManager.getLogger(LodManager.class.getName());

    private final LodDataSource source;
    private final LodBlockAppearance appearance;
    private final ChunkManager chunkManager;

    /* Eine Mesher-Instanz pro Worker-Thread (wiederverwendete Puffer, wie ChunkMesher) */
    private final ThreadLocal<LodMesher> meshers = ThreadLocal.withInitial(LodMesher::new);

    /* Soll-Zustand: regionKey -> Level. Nur auf dem Tick-/Render-Thread. */
    private final Map<Long, Integer> desired = new HashMap<>();
    /* Ist-Zustand der hochgeladenen Regionen (Bookkeeping für Resubmit-Entscheidungen) */
    private final Map<Long, Current> current = new HashMap<>();
    /* Bereits submittete, noch nicht abgeholte Jobs (gegen Doppel-Submits) */
    private final Set<Long> inflight = new HashSet<>();
    /* Worker -> Render-Thread */
    private final ConcurrentLinkedQueue<LodMeshResult> results = new ConcurrentLinkedQueue<>();

    /* Settings-Epoche: rd-/lodMaxDistance-/Toggle-Änderung entwertet alle gebauten Meshes */
    private int epoch = 0;

    /* Ring-Konfiguration der aktuellen Epoche — wird den Mesh-Jobs mitgegeben */
    private LodConfig config = LodConfig.of(16, 128);

    /* Anker der Level-Zuordnung: Zentrum der Spieler-Region des letzten Recompute (Blöcke) */
    private int anchorX = Integer.MIN_VALUE, anchorZ;

    /* Zustand der letzten Desired-Berechnung */
    private int lastRenderDistance = -1, lastLodMaxDistance = -1;
    private boolean lastEnabled = true;

    /* Spieler-Chunk (aktuell) — Zentrum der Masken-Scan-Zone */
    private int pcx, pcz;

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
        /* Debug-Pause friert auch das LOD ein (Desired-Set + Masken bleiben stehen, fertige
           Ergebnisse lädt der Renderer weiter hoch). */
        if (this.chunkManager.isLoadingPaused()) return;

        GameSettings settings = GameSettings.get();
        boolean enabled = settings.lodEnabled;
        int rd = settings.renderDistance;
        int lodMax = settings.lodMaxDistance;

        this.pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;

        boolean settingsChanged = enabled != this.lastEnabled || rd != this.lastRenderDistance
                || lodMax != this.lastLodMaxDistance;
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
        }

        if (!this.desired.isEmpty()) this.submitPass();
    }

    /** Baut den Soll-Zustand neu: alle Regionen bis zum äußersten Ring, Level pro Region. */
    private void recomputeDesired(boolean enabled) {
        this.desired.clear();
        if (!enabled) {
            this.current.clear(); // Renderer räumt die Meshes ab (isDesiredKey == false)
            return;
        }

        double outer = this.config.outerRadiusBlocks();
        int prx = Math.floorDiv(this.anchorX, LodMesher.REGION_BLOCKS);
        int prz = Math.floorDiv(this.anchorZ, LodMesher.REGION_BLOCKS);
        int radius = (int) Math.ceil((outer + LodMesher.HALF_DIAG) / LodMesher.REGION_BLOCKS);

        int[] counts = new int[this.config.maxLevel() + 1];
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int rx = prx + dx, rz = prz + dz;
                double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - this.anchorX;
                double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - this.anchorZ;
                double d = Math.sqrt(cx * cx + cz * cz);
                if (d - LodMesher.HALF_DIAG >= outer) continue; // ganz außerhalb
                /* Innen wird NICHT ausgeschlossen — dort clippt die Chunk-Maske zellgenau
                   (und füllt Lücken, solange Chunks noch laden). */
                int level = this.config.levelAt(d);
                this.desired.put(key(rx, rz), level);
                counts[level]++;
            }
        }

        /* Bookkeeping nicht mehr gewünschter Regionen aufräumen (Meshes räumt der Renderer ab) */
        this.current.keySet().removeIf(k -> !this.desired.containsKey(k));

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
                if (chunk != null && chunk.status == ChunkStatus.READY && chunk.isFullyUploaded()) {
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

        List<Candidate> candidates = null;
        for (Map.Entry<Long, Integer> e : this.desired.entrySet()) {
            long key = e.getKey();
            int level = e.getValue();
            if (this.inflight.contains(key)) continue;

            int rx = (int) (key >> 32), rz = (int) key;
            double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - (this.pcx * 32 + 16);
            double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - (this.pcz * 32 + 16);
            double distSq = cx * cx + cz * cz;

            Current c = this.current.get(key);
            boolean needs = c == null || c.level != level || c.epoch != this.epoch;
            int mask = Integer.MIN_VALUE; // noch nicht berechnet
            if (!needs && (c.mask != 0 || distSq < nearReal * nearReal)) {
                /* Masken-Diff: Chunk fertig geladen oder entladen → Region remeshen.
                   c.mask != 0 fängt den Unload-Fall auch außerhalb der Nahzone. */
                mask = this.computeMask(rx, rz);
                needs = mask != c.mask;
            }
            if (!needs) continue;

            if (candidates == null) candidates = new ArrayList<>();
            candidates.add(new Candidate(key, level, mask, distSq));
        }
        if (candidates == null) return;

        candidates.sort(Comparator.comparingDouble(Candidate::distSq));
        int submits = Math.min(MAX_SUBMITS_PER_TICK, candidates.size());
        for (int i = 0; i < submits; i++) {
            Candidate cand = candidates.get(i);
            this.inflight.add(cand.key);

            int rx = (int) (cand.key >> 32), rz = (int) cand.key;
            int level = cand.level, jobEpoch = this.epoch;
            int mask = cand.mask != Integer.MIN_VALUE ? cand.mask : this.computeMask(rx, rz);
            int jobAx = this.anchorX, jobAz = this.anchorZ;
            LodConfig jobConfig = this.config;
            this.chunkManager.submitLodTask(() -> this.results.add(this.meshers.get().mesh(
                    this.source, this.appearance, jobConfig, level, rx, rz,
                    jobEpoch, mask, jobAx, jobAz)));
        }
    }

    /* ------------------- API für den ChunkRenderer (Render-Thread) ------------------- */

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
        Integer want = this.desired.get(key);
        if (want == null || want != result.level() || result.epoch() != this.epoch) return false;
        this.current.put(key, new Current(result.level(), result.epoch(), result.mask()));
        return true;
    }

    /** true, solange die Region gewünscht ist — der Renderer räumt Meshes ab, sobald false. */
    public boolean isDesiredKey(long key) {
        return this.desired.containsKey(key);
    }
}
