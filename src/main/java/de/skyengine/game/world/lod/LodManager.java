package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Verwaltet die blockbasierten Heightmap-LOD-Ringe um den Spieler (Clipmap-Prinzip):
 * L0 = echte Chunks bis renderDistance, darüber Ringe mit äußeren Grenzen aus
 * {@code GameSettings.lodRings} (Level L → Zellgröße 2^L Blöcke, siehe {@link LodConfig}).
 *
 * <p>Datenquelle ist ausschließlich die abstrahierte {@link LodDataSource} (heute die pure
 * Generator-Funktion) — nie Chunk-Daten; LOD kann Spieleränderungen daher weder überschreiben
 * noch verzögern. Tick-getrieben aus {@code World.update()} (Tick und Render laufen auf
 * demselben Thread); Mesh-Jobs laufen mit niedrigster Priorität (unter Spieler-Remeshes und
 * Chunk-Load) auf den bestehenden Chunk-Workern und liefern über eine Queue an den
 * {@code ChunkRenderer}, der die Meshes in der OPAQUE-Arena hält.
 */
public class LodManager {

    /** Fertiges Regionen-Mesh (Worker → Render-Thread). Leere data = Region komplett geclippt. */
    public record LodMeshResult(int level, int rx, int rz, int epoch, int pcx, int pcz,
                                int[] data, float minY, float maxY) {}

    /* Stand einer hochgeladenen Region: Level + Settings-Epoche + Clip-Kreis des Meshes */
    private record Current(int level, int epoch, int pcx, int pcz) {}

    private record Candidate(long key, int level, double distSq) {}

    /* Deckelt die Executor-Queue — Jobs sind billig (nur Source-Samples), aber nah-zuerst. */
    private static final int MAX_SUBMITS_PER_TICK = 32;

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

    /* Settings-Epoche: Ring-/rd-/Toggle-Änderung entwertet alle gebauten Meshes */
    private int epoch = 0;

    /* Ring-Konfiguration der aktuellen Epoche — wird den Mesh-Jobs mitgegeben */
    private LodConfig config = new LodConfig(16, new int[]{32, 64, 128});

    /* Zustand der letzten Desired-Berechnung */
    private int lastRegionX = Integer.MIN_VALUE, lastRegionZ;
    private int lastRenderDistance = -1;
    private int[] lastRings = null;
    private boolean lastEnabled = true;

    /* Spielerposition der letzten Desired-Berechnung — Basis der Level-Zuordnung */
    private double px, pz;
    /* Spieler-Chunk (aktuell) — Mittelpunkt des Clip-Kreises für neue Mesh-Jobs */
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
        /* Debug-Pause friert auch das LOD ein (Desired-Set + Clip-Kreis bleiben stehen,
           fertige Ergebnisse lädt der Renderer weiter hoch) — so lässt sich in LOD-Gebiete
           fliegen, ohne dass das Clip-Loch mitwandert. */
        if (this.chunkManager.isLoadingPaused()) return;

        GameSettings settings = GameSettings.get();
        boolean enabled = settings.lodEnabled;
        int rd = settings.renderDistance;
        int[] rings = settings.lodRings;

        this.pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        this.pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        int prx = Math.floorDiv((int) Math.floor(player.x), LodMesher.REGION_BLOCKS);
        int prz = Math.floorDiv((int) Math.floor(player.z), LodMesher.REGION_BLOCKS);

        boolean settingsChanged = enabled != this.lastEnabled || rd != this.lastRenderDistance
                || !Arrays.equals(rings, this.lastRings);
        if (settingsChanged) {
            this.epoch++; // alle Meshes entwertet (Ringe/Clipping verschoben)
            this.config = new LodConfig(rd, rings.clone());
        }

        if (settingsChanged || prx != this.lastRegionX || prz != this.lastRegionZ) {
            this.px = player.x;
            this.pz = player.z;
            this.recomputeDesired(enabled);
            this.lastRegionX = prx;
            this.lastRegionZ = prz;
            this.lastEnabled = enabled;
            this.lastRenderDistance = rd;
            this.lastRings = rings.clone();
        }

        if (!this.desired.isEmpty()) this.submitPass();
    }

    /** Baut den Soll-Zustand neu: Annulus zwischen Clip-Radius und äußerstem Ring, Level pro Region. */
    private void recomputeDesired(boolean enabled) {
        this.desired.clear();
        if (!enabled) {
            this.current.clear(); // Renderer räumt die Meshes ab (isDesiredKey == false)
            return;
        }

        double outer = this.config.outerRadiusBlocks();
        double clip = this.config.clipRadius();
        int prx = Math.floorDiv((int) Math.floor(this.px), LodMesher.REGION_BLOCKS);
        int prz = Math.floorDiv((int) Math.floor(this.pz), LodMesher.REGION_BLOCKS);
        int radius = (int) Math.ceil((outer + LodMesher.HALF_DIAG) / LodMesher.REGION_BLOCKS);

        int[] counts = new int[this.config.rings().length + 1];
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int rx = prx + dx, rz = prz + dz;
                double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - this.px;
                double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - this.pz;
                double d = Math.sqrt(cx * cx + cz * cz);
                if (d - LodMesher.HALF_DIAG >= outer) continue;   // ganz außerhalb
                if (d + LodMesher.HALF_DIAG <= clip) continue;    // ganz im echten Chunk-Bereich
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

    /** Submittet fehlende/veraltete Regionen nah-zuerst, budgetiert pro Tick. */
    private void submitPass() {
        List<Candidate> candidates = null;
        for (Map.Entry<Long, Integer> e : this.desired.entrySet()) {
            long key = e.getKey();
            int level = e.getValue();
            if (this.inflight.contains(key)) continue;

            Current c = this.current.get(key);
            if (c != null && c.level == level && c.epoch == this.epoch && !this.clipStale(c, key)) continue;

            int rx = (int) (key >> 32), rz = (int) key;
            double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - this.px;
            double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - this.pz;
            if (candidates == null) candidates = new ArrayList<>();
            candidates.add(new Candidate(key, level, cx * cx + cz * cz));
        }
        if (candidates == null) return;

        candidates.sort(Comparator.comparingDouble(Candidate::distSq));
        int submits = Math.min(MAX_SUBMITS_PER_TICK, candidates.size());
        for (int i = 0; i < submits; i++) {
            Candidate cand = candidates.get(i);
            this.inflight.add(cand.key);

            int rx = (int) (cand.key >> 32), rz = (int) cand.key;
            int level = cand.level, jobEpoch = this.epoch;
            int jobPcx = this.pcx, jobPcz = this.pcz;
            double jobPx = this.px, jobPz = this.pz;
            LodConfig jobConfig = this.config;
            this.chunkManager.submitLodTask(() -> this.results.add(this.meshers.get().mesh(
                    this.source, this.appearance, jobConfig, level, rx, rz,
                    jobEpoch, jobPcx, jobPcz, jobPx, jobPz)));
        }
    }

    /**
     * true, wenn das gebaute Mesh der Region einen veralteten Clip-Kreis enthält: der Spieler-
     * Chunk hat sich bewegt UND die Region schneidet den alten oder neuen Clip-Kreis. Nur die
     * Randregionen am Übergang zu den echten Chunks remeshen dadurch bei Bewegung.
     */
    private boolean clipStale(Current c, long key) {
        if (c.pcx == this.pcx && c.pcz == this.pcz) return false;
        double reach = this.config.clipRadius() + LodMesher.HALF_DIAG;
        return this.regionNearChunk(key, c.pcx, c.pcz, reach)
                || this.regionNearChunk(key, this.pcx, this.pcz, reach);
    }

    private boolean regionNearChunk(long key, int chunkX, int chunkZ, double radius) {
        int rx = (int) (key >> 32), rz = (int) key;
        double dx = (rx + 0.5) * LodMesher.REGION_BLOCKS - (chunkX * 32 + 16);
        double dz = (rz + 0.5) * LodMesher.REGION_BLOCKS - (chunkZ * 32 + 16);
        return dx * dx + dz * dz < radius * radius;
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
        this.current.put(key, new Current(result.level(), result.epoch(), result.pcx(), result.pcz()));
        return true;
    }

    /** true, solange die Region gewünscht ist — der Renderer räumt Meshes ab, sobald false. */
    public boolean isDesiredKey(long key) {
        return this.desired.containsKey(key);
    }
}
