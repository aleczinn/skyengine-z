package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.light.LightEngine;
import de.skyengine.game.world.save.WorldStorage;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkManager {

    private final Logger logger = LogManager.getLogger(ChunkManager.class.getName());

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final WorldWorkerPool workerPool;
    private final ThreadPoolExecutor workers;
    private final boolean ownsWorkerPool;
    private final int workerCount;
    private final WorldGenerator generator;
    private final ChunkDecorator decorator;

    /* Worker -> render thread: Ein Batch wird immer komplett im selben Frame angewendet */
    private final ConcurrentLinkedQueue<MeshBatch> uploadQueue = new ConcurrentLinkedQueue<>();

    /* Spieler-Remeshes werden vor allen sonstigen Remesh-Uploads angewendet. */
    private final ConcurrentLinkedQueue<MeshBatch> playerUploadQueue = new ConcurrentLinkedQueue<>();

    /* Sonstige Remesh-Batches (TNT/Redstone/Fluid/Licht). */
    private final ConcurrentLinkedQueue<MeshBatch> priorityUploadQueue = new ConcurrentLinkedQueue<>();

    /* Chunks mit dirty Sections (gefüttert von Chunk.markSectionDirty, CAS-dedupliziert):
       processRemeshes arbeitet nur noch diese Queue ab, statt jeden Frame ALLE Chunks zu
       scannen (bei rd=16 waren das ~800 CHM-Iterationen pro Frame für typischerweise 0 Treffer). */
    private final ConcurrentLinkedQueue<Chunk> remeshQueue = new ConcurrentLinkedQueue<>();

    /* BlockEntity-Buchführung: setBlockEntity meldet Chunks hier an (beliebiger Thread), der
       Render-Thread übernimmt sie in chunksWithBlockEntities und räumt Entladene/Leere aus.
       Erspart BE-Renderer (pro Frame) und BE-Ticker (pro Tick) den Scan über ALLE Chunks. */
    private final ConcurrentLinkedQueue<Chunk> blockEntityAnnounceQueue = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<Chunk> chunksWithBlockEntities = new LinkedHashSet<>();

    /* Chunks mit beim Laden mitgebrachten Scheduled-Ticks (Announce vom Load-Worker über
       Chunk.announceTickRestore): Dimension.restorePendingScheduledTicks pollt nur diese Queue
       statt jeden Tick alle Chunks zu scannen (im Steady-State null Treffer). */
    private final ConcurrentLinkedQueue<Chunk> tickRestoreQueue = new ConcurrentLinkedQueue<>();

    /* READY gewordene Chunks (Announce vom Mesh-Worker). Dimension.processReadyChunks initialisiert
       beim ersten Mal transiente Zustände und gleicht bei jeder Meldung Redstone-Kanten ab.
       Bewusst hier und nicht früher: erst jetzt ist der Chunk selbst editierbar; Nachbarn sind
       durch das Mesh-Gate mindestens LIT und damit sicher lesbar. */
    private final ConcurrentLinkedQueue<Chunk> readyAnnounceQueue = new ConcurrentLinkedQueue<>();

    /* Tick-Thread -> Dimension: Koordinaten regulär entladener Chunks. Erst NACH dem Entfernen
       kann der verbleibende Nachbar Redstone an der nun offenen Kante korrekt neu berechnen. */
    private final ConcurrentLinkedQueue<Long> unloadAnnounceQueue = new ConcurrentLinkedQueue<>();

    /* Aktualitäts-Sequenz für Mesh-Ergebnisse, vergeben beim Job-SUBMIT (Render-Thread, seriell):
       ein später submitteter Job hat immer neuere Daten. Der Renderer verwirft in applyBatch
       Batches, deren Seq nicht neuer ist als das zuletzt angewendete Mesh der Section — sonst
       überschreibt ein Erst-Mesh aus der langen uploadQueue ein bereits angewendetes
       Priority-Remesh derselben Section (Dirty-Bit ist dann schon konsumiert → dauerhafte Naht). */
    private long nextMeshSeq = 1;
    private long nextRenderGeneration = 1;
    private volatile long activeRenderGeneration;

    private int renderDistance = 16; // in chunks

    /* Chunk-Persistenz: Lade-Quelle (statt Generierung) + Save-Ziel beim Unload. Von Dimension
       nach der Konstruktion gesetzt; null-tolerant (Tools/Tests ohne Persistenz). */
    private WorldStorage storage;

    /* Debug: friert Laden/Generieren/Unload ein (Remeshes von Spieler-Edits laufen weiter).
       volatile nur der Sichtbarkeit halber — gelesen wird auf dem Tick-/Render-Thread. */
    private volatile boolean loadingPaused = false;
    private boolean suppressSingleAnchorUnload;

    /* Zählt Entfernungen aus der chunks-Map (Unload-Loop + clearAllChunks). Der ChunkRenderer
       räumt seine Section-Meshes nur in Frames auf, in denen sich dieser Wert geändert hat,
       statt jeden Frame ALLE Meshes gegen die Map zu prüfen (gemessen ~250 µs/Frame im
       Steady-State). Nur auf dem Tick-/Render-Thread geschrieben und gelesen. */
    private int chunkRemovalVersion = 0;

    /* Begrenzt, wie viele Generierungs-Jobs pro Tick submitted werden.
       Hält die Executor-Queue kurz -> nahe Chunks neuer Positionen kommen schnell dran. */
    private static final int MAX_GENERATION_SUBMITS_PER_TICK = 64;

    /* Deckel für WARTENDE Lade-Jobs in der Worker-Queue. Tragend für die Sichtfeld-Prio:
       PrioTask trägt nur (prio, seq) — die Reihenfolge einmal eingereihter Jobs ist damit
       EINGEFROREN. Ohne Deckel stauen sich beim Weltstart tausende Jobs (der 64er-Deckel oben
       gilt nur für die Generierung, Dekoration und Erst-Mesh waren ungedeckelt) und ein
       Blickrichtungswechsel sortiert faktisch nichts mehr um. Mit Deckel bleibt die sortierte
       Offset-Liste in update() die echte Reihenfolge: hoch genug, dass die Worker über einen
       50-ms-Tick gesättigt bleiben, niedrig genug, dass ein 180°-Dreh binnen 1-2 Ticks greift. */
    private static final int LOAD_QUEUE_LIMIT = 128;

    /* Sichtfeld-Priorisierung, Stufe 1 = Sichtkegel. cos(75°) = FOV/2 plus ~30° Sicherheitsrand.
       Der Rand ist Absicht: die Pipeline ist NACHBAR-GEGATED (ein sichtbarer Chunk darf erst
       dekorieren/meshen, wenn alle 8 Nachbarn so weit sind). Ein exakter Frustum-Schnitt würde
       genau die Nachbarn am Rand des Sichtkegels nach hinten schieben — die sichtbaren Chunks
       müssten dann auf sie warten. */
    private static final float VIEW_CONE_COS = 0.2588F;

    /* Chunks in diesem Umkreis sind IMMER Stufe 1 (Physik/Kollision + Gating-Nachbarn des
       Spielerchunks) — auch wenn sie hinter dem Spieler liegen. */
    private static final int NEAR_ALWAYS = 2;

    /* Job-Prioritäten für den Worker-Pool: Remeshes überholen den Initial-Load. */
    private static final int PRIO_PLAYER_REMESH = 0;
    private static final int PRIO_SYSTEM_REMESH = 1;
    private static final int PRIO_LOAD = 2;
    private static final int PRIO_LIGHT = 3;

    /* AUSSTEHENDE Lade-Jobs (wartend + laufend) — Basis für LOAD_QUEUE_LIMIT und den Latch. */
    private final AtomicInteger pendingLoadTasks = new AtomicInteger();

    /* Scheduler-Telemetrie für L0-Load und Remeshes. */
    private final AtomicInteger queuedForegroundTasks = new AtomicInteger();
    private final AtomicInteger activeForegroundTasks = new AtomicInteger();

    /* Lade-Jobs, die update() in DIESEM Tick eingereiht hat (nur Tick-Thread). */
    private int loadSubmitsThisTick;

    /* Latch: true, sobald die Lade-Pipeline einmal ihren FIXPUNKT erreicht hat — nichts mehr
       einzureihen UND nichts mehr ausstehend. Der Ladebildschirm nutzt diesen Zustand.

       Fixpunkt statt "alle Chunks READY": die äußersten Ringe des Lade-Kreises finden ihre
       Gating-Nachbarn (Dekoration braucht 8× GENERATED, Erst-Mesh 8× DECORATED) außerhalb des
       Kreises NICHT und bleiben dauerhaft auf GENERATED/DECORATED stehen. Ein "alle READY"-Latch
       würde deshalb NIE auslösen. */
    private volatile boolean initialLoadComplete = false;

    /* Startdiagnose (nur Debug-Log): Nullpunkt aller "nach X ms"-Zeilen. Wird zusammen mit dem
       Latch zurueckgesetzt, damit "Chunks neu laden" und ein Renderdistanz-Wechsel erneut messen. */
    private volatile long loadStartNanos = System.nanoTime();

    /* Beim Lade-Fixpunkt noch offene Erst-Mesh-Batches. Zielzahl fuer die Upload-Diagnose des
       ChunkRenderers; -1 = der Fixpunkt wurde in diesem Durchlauf noch nicht erreicht. */
    private volatile int initialUploadBacklog = -1;

    /* Zaehlt jeden Neuaufbau des Radius. Der ChunkRenderer setzt daran seine
       Upload-Diagnose zurueck. */
    private volatile int loadCycle;

    /* (dx, dz)-Offsets im Kreis, einmal pro renderDistance gebaut; Reihenfolge wird per
       Score (Distanz + Blickrichtung) in update() umsortiert */
    private Offset[] loadOrder;
    private int loadOrderRadius = -1;

    /* Zustand des letzten Score-Sorts: neu sortiert wird nur bei Chunk-Wechsel oder >20° Drehung */
    private int lastSortPcx = Integer.MIN_VALUE, lastSortPcz;
    private float lastSortYaw;

    private static final class Offset {
        final int dx, dz;
        final float dist;
        float score;

        Offset(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
            this.dist = (float) Math.sqrt(dx * dx + dz * dz);
        }
    }

    /* One mesher per worker thread, reused (allocation-free) */
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(() ->
            new ChunkMesher(new ChunkMesher.MeshPhaseRecorder() {
                @Override
                public boolean enabled() {
                    return PerformanceProfiler.get().isEnabled();
                }

                @Override
                public void record(ChunkMesher.MeshPhase phase, long nanos) {
                    PerformanceProfiler.get().record(switch (phase) {
                        case PREPARE_AND_HALO -> PerformanceProfiler.WorkerSection.L0_MESH_PREPARE_HALO;
                        case FULL_CUBE_GREEDY -> PerformanceProfiler.WorkerSection.L0_MESH_FULL_CUBE_GREEDY;
                        case WATER_GREEDY -> PerformanceProfiler.WorkerSection.L0_MESH_WATER_GREEDY;
                        case GENERIC_MODELS -> PerformanceProfiler.WorkerSection.L0_MESH_GENERIC_MODELS;
                        case FINALIZE_AND_COPY -> PerformanceProfiler.WorkerSection.L0_MESH_FINALIZE_COPY;
                    }, nanos);
                }
            }));

    /* Dito für die Licht-Engine: eine Instanz ist NICHT threadsicher (BFS-Queues + 3x3-Kontext
       als Felder). Dimension hält eine eigene für die Edit-Updates auf dem Render-Thread. */
    private final ThreadLocal<LightEngine> lightEngines;

    public record MeshResult(int chunkX, int sectionY, int chunkZ, ChunkMesher.MeshData data, long meshSeq) {}

    public record MeshBatch(List<MeshResult> results, long renderGeneration,
                            PerformanceProfiler.AsyncToken enqueuedAt) {
        public MeshBatch(List<MeshResult> results, long renderGeneration) {
            this(results, renderGeneration, PerformanceProfiler.get().beginAsync());
        }
    }

    public ChunkManager(WorldGenerator generator, ChunkDecorator decorator) {
        this(generator, decorator, Math.max(2, Runtime.getRuntime().availableProcessors() - 2), true);
    }

    ChunkManager(WorldGenerator generator, ChunkDecorator decorator, int threads) {
        this(generator, decorator, threads, true);
    }

    public ChunkManager(WorldGenerator generator, ChunkDecorator decorator, boolean hasSkylight) {
        this(generator, decorator, new WorldWorkerPool(), true, hasSkylight);
    }

    ChunkManager(WorldGenerator generator, ChunkDecorator decorator, int threads, boolean hasSkylight) {
        this(generator, decorator, new WorldWorkerPool(threads), true, hasSkylight);
    }

    public ChunkManager(WorldGenerator generator, ChunkDecorator decorator,
                        WorldWorkerPool workerPool, boolean hasSkylight) {
        this(generator, decorator, workerPool, false, hasSkylight);
    }

    private ChunkManager(WorldGenerator generator, ChunkDecorator decorator,
                         WorldWorkerPool workerPool, boolean ownsWorkerPool, boolean hasSkylight) {
        this.generator = generator;
        this.decorator = decorator;
        this.lightEngines = ThreadLocal.withInitial(() -> new LightEngine(hasSkylight));
        this.workerPool = java.util.Objects.requireNonNull(workerPool, "workerPool");
        this.workers = workerPool.executor();
        this.workerCount = workerPool.workerCount();
        this.ownsWorkerPool = ownsWorkerPool;
    }

    private void submitTask(int prio, Runnable job) {
        /* execute statt submit: submit() würde in ein nicht-vergleichbares FutureTask wrappen */
        this.workers.execute(PrioTask.foreground(this, prio,
                this.workerPool.nextSequence(), job));
    }

    private void submitForegroundTask(int prio, Runnable job) {
        PerformanceProfiler.AsyncToken queuedAt = PerformanceProfiler.get().beginAsync();
        this.queuedForegroundTasks.incrementAndGet();
        this.submitTask(prio, () -> {
            this.queuedForegroundTasks.decrementAndGet();
            this.activeForegroundTasks.incrementAndGet();
            PerformanceProfiler.get().recordElapsed(
                    PerformanceProfiler.WorkerSection.L0_QUEUE_WAIT, queuedAt);
            try {
                job.run();
            } finally {
                this.activeForegroundTasks.decrementAndGet();
            }
        });
    }

    /**
     * Lade-Job (Generierung/Dekoration/Erst-Mesh) mit Buchführung für {@link #LOAD_QUEUE_LIMIT}
     * und {@link #initialLoadComplete}. Dekrementiert wird am ENDE des Jobs — der Zähler steht
     * also für AUSSTEHENDE Arbeit (wartend + laufend), sonst könnte der Latch auslösen, während
     * Worker noch mitten in einem Chunk stecken.
     */
    private void submitLoadTask(int priority, Runnable job) {
        this.pendingLoadTasks.incrementAndGet();
        this.loadSubmitsThisTick++;
        this.submitForegroundTask(priority, () -> {
            try {
                job.run();
            } finally {
                this.pendingLoadTasks.decrementAndGet();
            }
        });
    }

    /**
     * true, sobald die initiale Lade-Pipeline ihren Fixpunkt erreicht hat.
     */
    public boolean isInitialLoadComplete() {
        return this.initialLoadComplete;
    }

    /** Startdiagnose: Nullpunkt der "nach X ms"-Zeilen (Weltbetreten bzw. letzter Latch-Reset). */
    public long loadStartNanos() {
        return this.loadStartNanos;
    }

    /**
     * Startdiagnose: Anzahl der beim Lade-Fixpunkt noch offenen Erst-Mesh-Batches, oder -1,
     * solange der Fixpunkt nicht erreicht ist. Der {@link de.skyengine.graphics.world.ChunkRenderer}
     * zaehlt gegen diesen Wert herunter, statt die Queue auf leer zu pruefen.
     */
    public int initialUploadBacklog() {
        return this.initialUploadBacklog;
    }

    /**
     * Startdiagnose: Zyklusnummer des laufenden Radius-Aufbaus. Aendert sie sich, muessen
     * Der ChunkRenderer setzt daran seinen Messzustand zurueck.
     */
    public int loadCycle() {
        return this.loadCycle;
    }

    /** Setzt Latch und Startdiagnose zurueck — jeder Neuaufbau des Radius misst erneut. */
    private void resetLoadDiagnostics() {
        this.initialLoadComplete = false;
        this.loadStartNanos = System.nanoTime();
        this.initialUploadBacklog = -1;
        this.loadCycle++;
    }

    /** Debug: pausiert Laden/Generieren/Unload (Remeshes von Spieler-Edits laufen weiter). */
    public void setLoadingPaused(boolean paused) {
        this.loadingPaused = paused;
    }

    public boolean isLoadingPaused() {
        return loadingPaused;
    }

    /* PriorityBlockingQueue ist nicht stabil — seq hält gleiche Prioritäten in Einreihungs-
       Reihenfolge, sonst würde die Blickrichtungs-Sortierung der Lade-Jobs verwürfelt. */
    private record PrioTask(int prio, long seq, Runnable job)
            implements Runnable, Comparable<PrioTask> {

        static PrioTask foreground(ChunkManager owner, int priority, long sequence, Runnable job) {
            return new PrioTask(priority, sequence, job);
        }

        @Override
        public void run() {
            this.job.run();
        }

        @Override
        public int compareTo(PrioTask o) {
            if (this.prio != o.prio) return Integer.compare(this.prio, o.prio);
            return Long.compare(this.seq, o.seq);
        }
    }

    /**
     * Liefert die Offsets innerhalb des Kreises. Wird nur neu gebaut, wenn sich die
     * renderDistance ändert; die Reihenfolge sortiert update() nach Score um.
     */
    private Offset[] getLoadOrder() {
        if (this.loadOrderRadius != this.renderDistance) {
            List<Offset> list = new ArrayList<>();
            int r2 = this.renderDistance * this.renderDistance;

            for (int dx = -this.renderDistance; dx <= this.renderDistance; dx++) {
                for (int dz = -this.renderDistance; dz <= this.renderDistance; dz++) {
                    if (dx * dx + dz * dz <= r2) {
                        list.add(new Offset(dx, dz));
                    }
                }
            }

            this.loadOrder = list.toArray(new Offset[0]);
            this.loadOrderRadius = this.renderDistance;
            this.lastSortPcx = Integer.MIN_VALUE; // erzwingt den Score-Sort im nächsten update()
        }
        return this.loadOrder;
    }

    /**
     * Einmal pro TICK: Chunks erzeugen, generieren, dekorieren, Erst-Mesh, Unload.
     * Reihenfolge: nah vor fern, in Blickrichtung vor dahinter (Score-Sort).
     */
    public void update(EntityPlayer player) {
        PerformanceProfiler telemetryProfiler = PerformanceProfiler.get();
        if (telemetryProfiler.isEnabled()) {
            telemetryProfiler.set(PerformanceProfiler.Counter.L0_ACTIVE_JOBS, this.activeForegroundTasks.get());
            telemetryProfiler.set(PerformanceProfiler.Counter.L0_WAITING_JOBS, this.queuedForegroundTasks.get());
        }
        if (this.loadingPaused) return;

        int pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        int pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;

        Offset[] order = this.getLoadOrder();

        /* Sichtfeld-Priorisierung in ZWEI STUFEN: Stufe 1 = Sichtkegel (inkl. Rand, s.
           VIEW_CONE_COS) oder Nahbereich, Stufe 2 = alles andere; innerhalb einer Stufe
           entscheidet die Distanz. Der Bias (renderDistance + 1) ist größer als jede mögliche
           Stufe-1-Distanz -> kein Chunk hinter dem Spieler kann je einen sichtbaren überholen.
           Neu sortiert wird nur bei Chunk-Wechsel oder >20° Drehung (wrap-sicher). */
        float yawDelta = Math.abs(((player.yaw - this.lastSortYaw + 540.0f) % 360.0f) - 180.0f);
        if (pcx != this.lastSortPcx || pcz != this.lastSortPcz || yawDelta > 20.0f) {
            this.lastSortPcx = pcx;
            this.lastSortPcz = pcz;
            this.lastSortYaw = player.yaw;

            double yawRad = Math.toRadians(player.yaw);
            float vx = (float) Math.sin(yawRad);
            float vz = (float) -Math.cos(yawRad);
            float tier2Bias = this.renderDistance + 1;
            for (Offset o : order) {
                /* dist == 0 (Spielerchunk) hat keine Richtung -> immer Stufe 1 */
                float cos = o.dist > 0F ? (o.dx * vx + o.dz * vz) / o.dist : 1F;
                boolean inView = cos >= VIEW_CONE_COS || o.dist <= NEAR_ALWAYS;
                o.score = (inView ? 0F : tier2Bias) + o.dist;
            }
            Arrays.sort(order, Comparator.comparingDouble(o -> o.score));
        }

        int generationSubmits = 0;
        this.loadSubmitsThisTick = 0;

        /* 1. Create + generate + Erst-Mesh, in Score-Reihenfolge */
        for (Offset offset : order) {
            /* Queue-Deckel: nicht weiter einreihen, solange genug Lade-Arbeit aussteht. Bricht die
               Score-Reihenfolge NICHT — die Liste ist sortiert, der Rest kommt nächsten Tick
               (und wird nach einer Drehung vorher neu bewertet). */
            if (this.pendingLoadTasks.get() >= LOAD_QUEUE_LIMIT) break;

            int cx = pcx + offset.dx;
            int cz = pcz + offset.dz;
            long key = Chunk.key(cx, cz);

            Chunk chunk = this.chunks.get(key);
            if (chunk == null) {
                chunk = new Chunk(cx, cz);
                this.prepareManagedChunk(chunk);
                this.chunks.put(key, chunk);
            }

            if (chunk.status == ChunkStatus.NEW && generationSubmits < MAX_GENERATION_SUBMITS_PER_TICK) {
                generationSubmits++;
                chunk.status = ChunkStatus.GENERATING;
                Chunk finalChunk = chunk;
                this.submitLoadTask(PRIO_LOAD, () -> {
                    /* Persistenz zuerst: gespeicherte Chunks waren beim Save schon dekoriert
                       (nur READY-Chunks sind editierbar) -> DECORATED überspringt die
                       Doppel-Dekoration (gleiches Muster wie remeshAll). Befüllt wird vor dem
                       Status-Publish — exakt der Vertrag des Generators. */
                    PerformanceProfiler profiler = PerformanceProfiler.get();
                    long started = profiler.begin();
                    boolean loaded = this.storage != null && this.storage.loadChunk(finalChunk);
                    profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_DISK_LOAD, started);
                    if (loaded) {
                        finalChunk.status = ChunkStatus.DECORATED;
                    } else {
                        started = profiler.begin();
                        this.generator.generate(finalChunk);
                        profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_TERRAIN, started);
                        finalChunk.status = ChunkStatus.GENERATED;
                    }
                });
            }

            /* 2. Dekoration (Feature-Pass, Scheiben-Modell): sobald das 3x3-Umfeld Terrain hat.
               Der Manager wartet hier nur ab - der Job stößt selbst nie Generierung an. */
            if (chunk.status == ChunkStatus.GENERATED) {
                if (this.getAtLeast(cx, cz - 1, ChunkStatus.GENERATED) == null
                        || this.getAtLeast(cx, cz + 1, ChunkStatus.GENERATED) == null
                        || this.getAtLeast(cx - 1, cz, ChunkStatus.GENERATED) == null
                        || this.getAtLeast(cx + 1, cz, ChunkStatus.GENERATED) == null
                        || this.getDiagonalsAtLeast(cx, cz, ChunkStatus.GENERATED) == null) continue;

                chunk.status = ChunkStatus.DECORATING;
                Chunk finalChunk = chunk;
                this.submitLoadTask(PRIO_LOAD, () -> {
                    PerformanceProfiler profiler = PerformanceProfiler.get();
                    long started = profiler.begin();
                    this.decorator.decorate(finalChunk);
                    profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_FEATURES, started);
                    finalChunk.status = ChunkStatus.DECORATED;
                });
            }

            /* 3. Initial-Lighting: Heightmap + Himmelslicht-Flood, sobald alle 8 Nachbarn
               dekoriert sind - der Job LIEST Blöcke über die Ränder (schreibt Licht aber nur
               ins Zentrum). submitLoadTask und nicht submitTask: sonst zählt der Job nicht in
               pendingLoadTasks, sonst feuert der initialLoadComplete-Fixpunkt zu früh. */
            if (chunk.status == ChunkStatus.DECORATED) {
                Chunk north = this.getAtLeast(cx, cz - 1, ChunkStatus.DECORATED);
                Chunk south = this.getAtLeast(cx, cz + 1, ChunkStatus.DECORATED);
                Chunk west = this.getAtLeast(cx - 1, cz, ChunkStatus.DECORATED);
                Chunk east = this.getAtLeast(cx + 1, cz, ChunkStatus.DECORATED);
                Chunk[] diagonals = this.getDiagonalsAtLeast(cx, cz, ChunkStatus.DECORATED);
                if (north == null || south == null || west == null || east == null || diagonals == null) continue;

                chunk.status = ChunkStatus.LIGHTING;
                Chunk finalChunk = chunk;
                this.submitLoadTask(PRIO_LIGHT, () -> {
                    PerformanceProfiler profiler = PerformanceProfiler.get();
                    long started = profiler.begin();
                    LightEngine engine = this.lightEngines.get();
                    lockRead(finalChunk, north, south, west, east, diagonals);
                    try {
                        engine.lightInitial(finalChunk, north, south, west, east, diagonals);
                        /* LIT VOR dem Randaustausch: exchangeBorders seedet nur zu Nachbarn, die
                           schon LIT sind. Wer von zwei benachbarten Jobs später fertig wird, sieht
                           den anderen und tauscht BEIDE Richtungen aus - die Job-Reihenfolge wird
                           damit egal. Setzte man LIT erst danach, könnten sich zwei gleichzeitig
                           fertige Jobs verpassen: dauerhaft dunkle Naht an der Chunk-Grenze.
                           Der Preis (das Erst-Mesh darf jetzt schon starten und sieht am Rand
                           transient altes Licht) ist über die Dirty-Masken abgedeckt.
                           Innerhalb des try: exchangeBorders liest Nachbar-Blöcke. */
                        finalChunk.status = ChunkStatus.LIT;
                        engine.exchangeBorders(finalChunk, north, south, west, east, diagonals);
                    } finally {
                        unlockRead(finalChunk, north, south, west, east, diagonals);
                    }
                    profiler.recordElapsed(PerformanceProfiler.WorkerSection.L0_INITIAL_LIGHT, started);
                });
            }

            /* 4. Erst-Mesh: alle 16 Sections, sobald alle 8 Nachbarn belichtet sind - erst dann
               stehen die Feature-Scheiben an den Rändern fest (Diagonalen braucht zusätzlich
               die Fluid-Eckhöhen-Berechnung an Chunk-Ecken) UND das Nachbar-Licht steht, das
               der Mesher fürs Corner-Smoothing sampelt.
               Jede Section als eigener Batch, damit der Upload über Frames verteilt wird. */
            if (chunk.status == ChunkStatus.LIT) {
                /* Dedicated server: lighting is the final CPU representation. Meshing and
                   upload queues only exist once a renderer has explicitly attached. */
                if (this.activeRenderGeneration == 0) {
                    chunk.status = ChunkStatus.READY;
                    this.readyAnnounceQueue.add(chunk);
                    continue;
                }
                Chunk north = this.getAtLeast(cx, cz - 1, ChunkStatus.LIT);
                Chunk south = this.getAtLeast(cx, cz + 1, ChunkStatus.LIT);
                Chunk west = this.getAtLeast(cx - 1, cz, ChunkStatus.LIT);
                Chunk east = this.getAtLeast(cx + 1, cz, ChunkStatus.LIT);
                Chunk[] diagonals = this.getDiagonalsAtLeast(cx, cz, ChunkStatus.LIT);
                if (north == null || south == null || west == null || east == null || diagonals == null) continue;

                chunk.status = ChunkStatus.MESHING;
                Chunk finalChunk = chunk;
                long meshSeq = this.nextMeshSeq++;
                long renderGeneration = this.activeRenderGeneration;
                this.submitLoadTask(PRIO_LIGHT, () -> {
                    ChunkMesher mesher = this.meshers.get();
                    List<MeshResult> batch = new ArrayList<>(Chunk.SECTIONS);
                    lockRead(finalChunk, north, south, west, east, diagonals);
                    try {
                        for (int s = 0; s < Chunk.SECTIONS; s++) {
                            long started = PerformanceProfiler.get().begin();
                            batch.add(new MeshResult(finalChunk.chunkX, s, finalChunk.chunkZ,
                                    mesher.mesh(finalChunk, s, north, south, west, east, diagonals), meshSeq));
                            PerformanceProfiler.get().recordElapsed(
                                    PerformanceProfiler.WorkerSection.L0_INITIAL_MESH, started);
                        }
                    } finally {
                        unlockRead(finalChunk, north, south, west, east, diagonals);
                    }
                    /* EIN Batch je Chunk statt 16 — der Renderer deckelt die Queue auf
                       MAX_UPLOADS_PER_FRAME EINTRAEGE, nicht auf Sections; je Section ein Eintrag
                       hiess also 0,5 Chunks pro Frame. Gemessen bei 5120x1440: 5072 offene Batches
                       beim Lade-Fixpunkt und 2,2 s, in denen der Spieler in einer halb leeren Welt
                       steht. Enqueue ausserhalb der Read-Locks wie im Remesh-Pfad. Der Chunk wird
                       damit außerdem in EINEM Frame vollständig. */
                    if (renderGeneration == this.activeRenderGeneration) {
                        this.uploadQueue.add(new MeshBatch(batch, renderGeneration));
                        finalChunk.status = ChunkStatus.READY;
                        this.readyAnnounceQueue.add(finalChunk);
                    }
                });
            }
        }

        /* Fixpunkt der Lade-Pipeline: nichts mehr einzureihen und nichts mehr ausstehend. */
        if (!this.initialLoadComplete && this.loadSubmitsThisTick == 0 && this.pendingLoadTasks.get() == 0) {
            this.initialLoadComplete = true;
            /* Startdiagnose: genau HIER schliesst auch der Ladebildschirm (GuiWorldLoading haengt
               am selben Latch). Die drei Werte sind die Vorher/Nachher-Basis fuer Aenderungen an
               Upload-Granularitaet und Job-Prioritaeten. size() der ConcurrentLinkedQueue ist O(n)
               und wird deshalb GENAU EINMAL gerufen, nie pro Frame. */
            int sichtbar = 0;
            for (Chunk chunk : this.chunks.values()) {
                if (chunk.isFullyUploaded()) sichtbar++;
            }
            this.initialUploadBacklog = this.uploadQueue.size();
            this.logger.debug("Initialer Chunk-Load fertig nach "
                    + ((System.nanoTime() - this.loadStartNanos) / 1_000_000L)
                    + " ms; " + sichtbar + "/"
                    + this.chunks.size() + " Chunks sichtbar, " + this.initialUploadBacklog
                    + " Erst-Mesh-Batches offen");
        }

        if (this.suppressSingleAnchorUnload) return;

        /* 5. Unload chunks far outside the render distance.
           READY, LIT, DECORATED und GENERATED dürfen weg - nur laufende Jobs
           (GENERATING/DECORATING/LIGHTING/MESHING) bleiben, bis sie fertig sind,
           sonst arbeiten Worker auf entfernten Chunks. */
        int unloadDist = this.renderDistance + 2;
        unloadOutside(chunk -> {
            int dx = chunk.chunkX - pcx, dz = chunk.chunkZ - pcz;
            return dx * dx + dz * dz <= unloadDist * unloadDist;
        });
    }

    private void unloadOutside(java.util.function.Predicate<Chunk> retainedByInterest) {
        Iterator<Map.Entry<Long, Chunk>> it = this.chunks.entrySet().iterator();
        while (it.hasNext()) {
            Chunk chunk = it.next().getValue();
            if (retainedByInterest.test(chunk)) continue;

            ChunkStatus status = chunk.status;
            if (status == ChunkStatus.READY || status == ChunkStatus.LIT
                    || status == ChunkStatus.DECORATED
                    || status == ChunkStatus.GENERATED || status == ChunkStatus.NEW) {
                /* Save-Gate: modifizierte Chunks bleiben in der Map, bis der IO-Thread den
                   Save abgeschlossen hat (saveQueued-Protokoll). Löst zugleich die
                   Unload/Reload-Race — kommt der Spieler zurück, ist der Chunk noch da. */
                if (this.storage != null && (chunk.isModified() || chunk.saveQueued)) {
                    if (!chunk.saveQueued) {
                        chunk.materializeFallingBlocks();
                        chunk.saveQueued = true;
                        this.storage.enqueueSave(chunk);
                    }
                    continue;
                }
                it.remove();
                this.unloadAnnounceQueue.add(Chunk.key(chunk.chunkX, chunk.chunkZ));
                /* Renderer disposes the GL meshes when it notices the chunk is gone */
                this.chunkRemovalVersion++;
            }
        }
    }

    /**
     * Setzt alle fertigen Chunks auf LIT zurück (NICHT DECORATED oder GENERATED — sonst würden
     * sie erneut dekoriert bzw. das unveränderte Licht komplett neu geflutet) — der normale
     * Lade-Pfad ({@link #update}) meshed sie dann progressiv neu (Blickrichtungs-Score,
     * Upload-Budget). Für Settings, die ins gebackene Mesh eingehen (z.B. AO, Laub-Qualität):
     * alte Meshes bleiben sichtbar, bis der Ersatz hochgeladen ist. Chunks, die gerade
     * GENERATING/DECORATING/LIGHTING/MESHING sind, bleiben unberührt.
     */
    public void remeshAll() {
        for (Chunk chunk : this.chunks.values()) {
            if (chunk.status == ChunkStatus.READY) chunk.status = ChunkStatus.LIT;
        }
    }

    /**
     * Verwirft ALLE Chunks (Debug-F8). Muss statt {@code getChunks().clear()} benutzt werden,
     * damit {@link #chunkRemovalVersion} bumpt — sonst bemerkt der ChunkRenderer die
     * Entfernung nicht und die alten Meshes blieben als Geistergeometrie stehen.
     */
    public void clearAllChunks() {
        /* Modifizierte Chunks vorm Verwerfen sichern (F8-Reload wäre sonst Datenverlust).
           Der Save-Job hält seine eigene Chunk-Referenz — das Entfernen aus der Map direkt
           danach ist unkritisch, der Snapshot läuft unter dem Chunk-Read-Lock. */
        if (this.storage != null) {
            for (Chunk chunk : this.chunks.values()) {
                if (chunk.isModified() && !chunk.saveQueued) {
                    chunk.saveQueued = true;
                    this.storage.enqueueSave(chunk);
                }
            }
        }
        this.chunks.clear();
        /* Ausstehende Upload-Batches der alten Chunk-Objekte verwerfen — applyBatch würde sie
           sonst auf die NEU angelegten Chunks derselben Koordinaten anwenden (alte Geometrie +
           uploadedSections-Inflation). Noch laufende Worker-Jobs können danach vereinzelt
           einreihen; diese Nachzügler heilt der Erst-Mesh der neuen Chunks (höhere meshSeq). */
        this.uploadQueue.clear();
        this.playerUploadQueue.clear();
        this.priorityUploadQueue.clear();
        this.remeshQueue.clear(); // alte Chunk-Objekte; Neuanlagen melden sich selbst wieder an
        this.blockEntityAnnounceQueue.clear();
        this.tickRestoreQueue.clear();
        this.readyAnnounceQueue.clear();
        this.unloadAnnounceQueue.clear();
        this.chunksWithBlockEntities.clear();
        this.chunkRemovalVersion++;
        this.resetLoadDiagnostics();
    }

    public int getChunkRemovalVersion() {
        return chunkRemovalVersion;
    }

    /**
     * Einmal pro FRAME (z.B. aus Dimension.render) aufrufen: stößt Remeshes
     * für dirty Sections sofort an, statt auf den nächsten Tick zu warten.
     */
    public void processRemeshes() {
        /* Nur so viele Polls wie beim Eintritt eingereiht — Requeues (noch nicht READY,
           Nachbarn fehlen) kommen sonst im selben Frame endlos wieder dran. */
        int pending = this.remeshQueue.size();
        for (int i = 0; i < pending; i++) {
            Chunk chunk = this.remeshQueue.poll();
            if (chunk == null) break;
            /* Buchführung ZUERST löschen: eine Markierung ab hier reiht neu ein — sonst
               ginge ein Mark zwischen Poll und Verarbeitung verloren. */
            chunk.clearRemeshEnqueued();

            /* Entladene/ersetzte Chunks austragen (die Queue hielte sie sonst am Leben). */
            if (this.chunks.get(Chunk.key(chunk.chunkX, chunk.chunkZ)) != chunk) continue;
            if (!chunk.hasDirtySections()) continue;
            if (chunk.status != ChunkStatus.READY) {
                /* Markierung vor READY (Dimension.markDirty ab LIT, Licht-Randaustausch):
                   dranbleiben — der Erst-Mesh konsumiert die Maske nicht. */
                chunk.enqueueRemesh();
                continue;
            }

            /* Mindestens LIT: der Dekorator schreibt lock-frei (FeaturePlacer) — ein Remesh-Job
               dürfte einen DECORATING-Nachbarn nicht lesen (Read-Lock schützt nur gegen
               setBlockRaw); ab DECORATED wird nur noch mit Write-Lock geschrieben. LIT statt
               DECORATED, weil der Mesher zusätzlich Nachbar-LICHT fürs Corner-Smoothing
               sampelt — ein Nachbar auf DECORATED/LIGHTING hätte noch keins. */
            Chunk north = this.getAtLeast(chunk.chunkX, chunk.chunkZ - 1, ChunkStatus.LIT);
            Chunk south = this.getAtLeast(chunk.chunkX, chunk.chunkZ + 1, ChunkStatus.LIT);
            Chunk west = this.getAtLeast(chunk.chunkX - 1, chunk.chunkZ, ChunkStatus.LIT);
            Chunk east = this.getAtLeast(chunk.chunkX + 1, chunk.chunkZ, ChunkStatus.LIT);
            Chunk[] diagonals = this.getDiagonalsAtLeast(chunk.chunkX, chunk.chunkZ, ChunkStatus.LIT);
            /* Nachbarn fehlen (Weltrand): Maske NICHT konsumieren, bleibt für später erhalten */
            if (north == null || south == null || west == null || east == null || diagonals == null) {
                chunk.enqueueRemesh();
                continue;
            }

            Chunk.DirtySections dirty = chunk.consumeDirtySections();
            int mask = dirty.mask();
            if (mask == 0) continue;

            long meshSeq = this.nextMeshSeq++;
            long renderGeneration = this.activeRenderGeneration;
            int priority = dirty.player() ? PRIO_PLAYER_REMESH : PRIO_SYSTEM_REMESH;
            this.submitForegroundTask(priority, () -> {
                ChunkMesher mesher = this.meshers.get();
                List<MeshResult> batch = new ArrayList<>(Integer.bitCount(mask));
                lockRead(chunk, north, south, west, east, diagonals);
                try {
                    for (int s = 0; s < Chunk.SECTIONS; s++) {
                        if ((mask & (1 << s)) == 0) continue;
                        long started = PerformanceProfiler.get().begin();
                        batch.add(new MeshResult(chunk.chunkX, s, chunk.chunkZ,
                                mesher.mesh(chunk, s, north, south, west, east, diagonals), meshSeq));
                        PerformanceProfiler.get().recordElapsed(
                                PerformanceProfiler.WorkerSection.L0_REMESH, started);
                    }
                } finally {
                    unlockRead(chunk, north, south, west, east, diagonals);
                }
                /* Enqueue außerhalb des Locks ist ok: überholen sich zwei Remesh-Jobs derselben
                   Section hier, verwirft applyBatch den älteren über die meshSeq-Prüfung. */
                if (renderGeneration == this.activeRenderGeneration) {
                    (dirty.player() ? this.playerUploadQueue : this.priorityUploadQueue)
                            .add(new MeshBatch(batch, renderGeneration));
                }
            });
        }
    }

    /* Read-Locks aller am Mesh beteiligten Chunks (self + 4 Kardinale + 4 Diagonalen) gegen
       gleichzeitige Block-Edits auf dem Render-Thread. Read-Locks sind untereinander kompatibel
       und der Writer (setBlockRaw) hält nur einen Lock -> kein Deadlock. Alle neun sind hier nie null. */
    private static void lockRead(Chunk a, Chunk b, Chunk c, Chunk d, Chunk e, Chunk[] diagonals) {
        a.readLock().lock();
        b.readLock().lock();
        c.readLock().lock();
        d.readLock().lock();
        e.readLock().lock();
        for (Chunk diag : diagonals) diag.readLock().lock();
    }

    private static void unlockRead(Chunk a, Chunk b, Chunk c, Chunk d, Chunk e, Chunk[] diagonals) {
        for (int i = diagonals.length - 1; i >= 0; i--) diagonals[i].readLock().unlock();
        e.readLock().unlock();
        d.readLock().unlock();
        c.readLock().unlock();
        b.readLock().unlock();
        a.readLock().unlock();
    }

    /**
     * Die 4 diagonalen Nachbarn (Reihenfolge NW, NE, SW, SE — wie {@code FluidGeometry.sample}
     * sie erwartet), oder null, wenn einer den Mindest-Status noch nicht erreicht hat.
     */
    private Chunk[] getDiagonalsAtLeast(int cx, int cz, ChunkStatus min) {
        Chunk nw = this.getAtLeast(cx - 1, cz - 1, min);
        Chunk ne = this.getAtLeast(cx + 1, cz - 1, min);
        Chunk sw = this.getAtLeast(cx - 1, cz + 1, min);
        Chunk se = this.getAtLeast(cx + 1, cz + 1, min);
        if (nw == null || ne == null || sw == null || se == null) return null;
        return new Chunk[]{nw, ne, sw, se};
    }

    /** Chunk an (cx, cz), sofern er mindestens den Status {@code min} erreicht hat — sonst null. */
    private Chunk getAtLeast(int cx, int cz, ChunkStatus min) {
        Chunk chunk = this.chunks.get(Chunk.key(cx, cz));
        if (chunk == null) return null;
        return chunk.status.isAtLeast(min) ? chunk : null;
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return this.chunks.get(Chunk.key(chunkX, chunkZ));
    }

    /**
     * Dedicated-server interest update. Each player contributes its normal prioritized load
     * radius. Loading uses the shared chunk map; unloading happens once against the union of
     * spatially bucketed player interests, so no player can evict another player's chunks and
     * the map still releases terrain after every player has left it.
     */
    public void updatePlayers(Iterable<EntityPlayer> players) {
        java.util.List<EntityPlayer> anchors = new java.util.ArrayList<>();
        for (EntityPlayer player : players) anchors.add(player);
        if (anchors.isEmpty()) return;
        this.suppressSingleAnchorUnload = true;
        try {
            for (EntityPlayer player : anchors) this.update(player);
        } finally {
            this.suppressSingleAnchorUnload = false;
        }
        int unloadDistance = this.renderDistance + 2;
        int unloadDistanceSquared = unloadDistance * unloadDistance;
        java.util.Map<Long, java.util.List<EntityPlayer>> anchorBuckets = new java.util.HashMap<>();
        for (EntityPlayer player : anchors) {
            int playerChunkX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
            int playerChunkZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
            int bucketX = Math.floorDiv(playerChunkX, unloadDistance);
            int bucketZ = Math.floorDiv(playerChunkZ, unloadDistance);
            anchorBuckets.computeIfAbsent(Chunk.key(bucketX, bucketZ), ignored -> new java.util.ArrayList<>())
                    .add(player);
        }
        unloadOutside(chunk -> {
            int bucketX = Math.floorDiv(chunk.chunkX, unloadDistance);
            int bucketZ = Math.floorDiv(chunk.chunkZ, unloadDistance);
            for (int bucketOffsetZ = -1; bucketOffsetZ <= 1; bucketOffsetZ++) {
                for (int bucketOffsetX = -1; bucketOffsetX <= 1; bucketOffsetX++) {
                    java.util.List<EntityPlayer> nearby = anchorBuckets.get(Chunk.key(
                            bucketX + bucketOffsetX, bucketZ + bucketOffsetZ));
                    if (nearby == null) continue;
                    for (EntityPlayer player : nearby) {
                        int playerChunkX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
                        int playerChunkZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
                        int dx = chunk.chunkX - playerChunkX, dz = chunk.chunkZ - playerChunkZ;
                        if (dx * dx + dz * dz <= unloadDistanceSquared) return true;
                    }
                }
            }
            return false;
        });
    }

    /**
     * Installs an already generated and lit L0 chunk received from an authoritative server.
     * The normal generator/update loop must not be used by replicated client worlds.
     */
    public void installReplicatedChunk(Chunk chunk) {
        java.util.Objects.requireNonNull(chunk, "chunk");
        if (!chunk.status.isAtLeast(ChunkStatus.LIT)) {
            throw new IllegalArgumentException("Replicated chunk must contain final lighting");
        }
        this.prepareManagedChunk(chunk);
        Chunk previous = this.chunks.put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        if (previous != null && previous != chunk) {
            this.chunkRemovalVersion++;
            this.markReplicatedNeighboursDirty(chunk.chunkX, chunk.chunkZ);
        }
        this.scheduleReplicatedMeshesAround(chunk.chunkX, chunk.chunkZ);
    }

    /** Removes a server-replicated chunk and invalidates its GPU residency. */
    public void removeReplicatedChunk(int chunkX, int chunkZ) {
        Chunk removed = this.chunks.remove(Chunk.key(chunkX, chunkZ));
        if (removed == null) return;
        this.unloadAnnounceQueue.add(Chunk.key(chunkX, chunkZ));
        this.chunkRemovalVersion++;
    }

    private void prepareManagedChunk(Chunk chunk) {
        chunk.beginRenderGeneration(this.activeRenderGeneration);
        chunk.remeshQueue = this.remeshQueue;
        chunk.blockEntityAnnounceQueue = this.blockEntityAnnounceQueue;
        chunk.tickRestoreQueue = this.tickRestoreQueue;
    }

    private void scheduleReplicatedMeshesAround(int chunkX, int chunkZ) {
        if (this.activeRenderGeneration == 0) return;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                this.tryScheduleReplicatedMesh(chunkX + dx, chunkZ + dz);
            }
        }
    }

    private void tryScheduleReplicatedMesh(int chunkX, int chunkZ) {
        Chunk chunk = this.getAtLeast(chunkX, chunkZ, ChunkStatus.LIT);
        if (chunk == null || chunk.status != ChunkStatus.LIT) return;
        Chunk north = this.getAtLeast(chunkX, chunkZ - 1, ChunkStatus.LIT);
        Chunk south = this.getAtLeast(chunkX, chunkZ + 1, ChunkStatus.LIT);
        Chunk west = this.getAtLeast(chunkX - 1, chunkZ, ChunkStatus.LIT);
        Chunk east = this.getAtLeast(chunkX + 1, chunkZ, ChunkStatus.LIT);
        Chunk[] diagonals = this.getDiagonalsAtLeast(chunkX, chunkZ, ChunkStatus.LIT);
        if (north == null || south == null || west == null || east == null || diagonals == null) return;

        chunk.status = ChunkStatus.MESHING;
        long meshSeq = this.nextMeshSeq++;
        long renderGeneration = this.activeRenderGeneration;
        this.submitForegroundTask(PRIO_LOAD, () -> {
            ChunkMesher mesher = this.meshers.get();
            List<MeshResult> batch = new ArrayList<>(Chunk.SECTIONS);
            lockRead(chunk, north, south, west, east, diagonals);
            try {
                for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
                    long started = PerformanceProfiler.get().begin();
                    batch.add(new MeshResult(chunk.chunkX, sectionY, chunk.chunkZ,
                            mesher.mesh(chunk, sectionY, north, south, west, east, diagonals), meshSeq));
                    PerformanceProfiler.get().recordElapsed(
                            PerformanceProfiler.WorkerSection.L0_INITIAL_MESH, started);
                }
            } finally {
                unlockRead(chunk, north, south, west, east, diagonals);
            }
            if (renderGeneration == this.activeRenderGeneration
                    && this.chunks.get(Chunk.key(chunk.chunkX, chunk.chunkZ)) == chunk) {
                this.uploadQueue.add(new MeshBatch(batch, renderGeneration));
                chunk.status = ChunkStatus.READY;
                this.readyAnnounceQueue.add(chunk);
            }
        });
    }

    private void markReplicatedNeighboursDirty(int chunkX, int chunkZ) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                Chunk neighbour = this.getChunk(chunkX + dx, chunkZ + dz);
                if (neighbour != null && neighbour.status == ChunkStatus.READY) {
                    neighbour.markSectionsDirty((1 << Chunk.SECTIONS) - 1);
                }
            }
        }
    }

    /** Alle aktuell geladenen Chunks (z.B. fürs BlockEntity-Ticking). */
    /**
     * Chunks mit mindestens einem BlockEntity (nur Render-/Tick-Thread): übernimmt zuerst die
     * Announce-Queue (setBlockEntity läuft auch auf Workern, z.B. beim Chunk-Restore) und räumt
     * entladene/ersetzte sowie inzwischen BE-leere Chunks aus. Die Menge ist klein — das
     * removeIf ist billiger als der frühere Scan über ALLE geladenen Chunks.
     */
    public java.util.Collection<Chunk> chunksWithBlockEntities() {
        Chunk announced;
        while ((announced = this.blockEntityAnnounceQueue.poll()) != null) {
            this.chunksWithBlockEntities.add(announced);
        }
        this.chunksWithBlockEntities.removeIf(chunk ->
                this.chunks.get(Chunk.key(chunk.chunkX, chunk.chunkZ)) != chunk || chunk.blockEntities().isEmpty());
        return this.chunksWithBlockEntities;
    }

    /* Tick-Restore-Queue (nur Tick-Thread konsumiert; Producer ist der Load-Worker). */

    public int tickRestorePending() {
        return this.tickRestoreQueue.size();
    }

    public Chunk pollTickRestore() {
        return this.tickRestoreQueue.poll();
    }

    /** Anzahl gemeldeter READY-Chunks (Poll-Deckel gegen Endlos-Requeue im selben Tick). */
    public int readyAnnouncePending() {
        return this.readyAnnounceQueue.size();
    }

    public Chunk pollReadyAnnounce() {
        return this.readyAnnounceQueue.poll();
    }

    public void requeueReadyAnnounce(Chunk chunk) {
        this.readyAnnounceQueue.add(chunk);
    }

    public int unloadAnnouncePending() {
        return this.unloadAnnounceQueue.size();
    }

    public Long pollUnloadAnnounce() {
        return this.unloadAnnounceQueue.poll();
    }

    public void requeueTickRestore(Chunk chunk) {
        this.tickRestoreQueue.add(chunk);
    }

    public java.util.Collection<Chunk> loadedChunks() {
        return this.chunks.values();
    }

    public ConcurrentLinkedQueue<MeshBatch> getUploadQueue() {
        return uploadQueue;
    }

    /** Direkte Spieler-Edits - vor jeder anderen Upload-Klasse zu leeren. */
    public ConcurrentLinkedQueue<MeshBatch> getPlayerUploadQueue() {
        return playerUploadQueue;
    }

    /** Remesh-Batches (Edits/Fluid) — vom Renderer vor der normalen Queue zu leeren. */
    public ConcurrentLinkedQueue<MeshBatch> getPriorityUploadQueue() {
        return priorityUploadQueue;
    }

    public ConcurrentHashMap<Long, Chunk> getChunks() {
        return chunks;
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(int renderDistance) {
        int clamped = Math.max(2, renderDistance);
        /* Unveränderter Wert darf die Startdiagnose nicht neu beginnen. */
        if (clamped == this.renderDistance) return;
        this.renderDistance = clamped;
        /* loadOrder wird beim nächsten update() automatisch neu berechnet */
        this.resetLoadDiagnostics();
    }

    /**
     * Starts a new GPU residency generation without discarding terrain or savegame state.
     * Existing READY chunks are sent through the normal full-mesh pipeline again.
     */
    public long attachRenderer() {
        if (this.activeRenderGeneration != 0) {
            throw new IllegalStateException("Dimension already has an active render view");
        }
        long generation = this.nextRenderGeneration++;
        this.activeRenderGeneration = generation;
        this.clearRenderQueues();
        for (Chunk chunk : this.chunks.values()) {
            chunk.beginRenderGeneration(generation);
            chunk.discardDirtySectionsForFullRemesh();
            if (chunk.status == ChunkStatus.READY || chunk.status == ChunkStatus.MESHING) {
                chunk.status = ChunkStatus.LIT;
            }
        }
        this.resetLoadDiagnostics();
        return generation;
    }

    /** Invalidates all GPU residency owned by the specified view. */
    public void detachRenderer(long generation) {
        if (generation == 0 || generation != this.activeRenderGeneration) return;
        this.activeRenderGeneration = 0;
        this.clearRenderQueues();
        for (Chunk chunk : this.chunks.values()) {
            chunk.endRenderGeneration(generation);
            chunk.discardDirtySectionsForFullRemesh();
            if (chunk.status == ChunkStatus.READY || chunk.status == ChunkStatus.MESHING) {
                chunk.status = ChunkStatus.LIT;
            }
        }
    }

    public boolean isRenderGenerationActive(long generation) {
        return generation != 0 && generation == this.activeRenderGeneration;
    }

    /** The loading screen waits for actual GPU uploads around the player. */
    public boolean isInitialRenderReady(EntityPlayer player) {
        return this.initialLoadComplete && this.initialRenderProgress(player) >= 1F;
    }

    public float initialRenderProgress(EntityPlayer player) {
        if (player == null) return 0F;
        int pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        int pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        /* At render distance 2, corner chunks of a 3x3 area cannot reach READY because their
           own 3x3 meshing neighbourhood extends beyond the circular load radius. */
        int radius = this.renderDistance >= 3 ? 1 : 0;
        int target = (radius * 2 + 1) * (radius * 2 + 1);
        int uploaded = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                Chunk chunk = this.getChunk(pcx + dx, pcz + dz);
                if (chunk != null && chunk.status == ChunkStatus.READY && chunk.isFullyUploaded()) uploaded++;
            }
        }
        return uploaded / (float) target;
    }

    private void clearRenderQueues() {
        this.uploadQueue.clear();
        this.playerUploadQueue.clear();
        this.priorityUploadQueue.clear();
        this.remeshQueue.clear();
    }

    /** Chunk-Persistenz-Anbindung (von Dimension nach der Konstruktion gesetzt). */
    public void setStorage(WorldStorage storage) {
        this.storage = storage;
    }

    public void dispose() {
        if (!this.ownsWorkerPool) {
            this.awaitWorkerTasks();
            return;
        }
        this.workerPool.dispose();
    }

    /** Render-Thread-Barriere vor dem Abbau einer aktiven DimensionView. */
    public void awaitWorkerTasks() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (this.queuedForegroundTasks.get() + this.activeForegroundTasks.get() > 0
                && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        if (this.queuedForegroundTasks.get() + this.activeForegroundTasks.get() > 0) {
            this.logger.warning("Dimensions-Worker waren nach 10 s noch aktiv");
        }
    }
}
