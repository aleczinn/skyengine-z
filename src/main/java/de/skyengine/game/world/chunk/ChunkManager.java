package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.light.LightEngine;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.game.world.save.WorldStorage;
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
import java.util.concurrent.atomic.AtomicLong;

public class ChunkManager {

    private final Logger logger = LogManager.getLogger(ChunkManager.class.getName());

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final WorldGenerator generator;
    private final ChunkDecorator decorator;

    /* Worker -> render thread: Ein Batch wird immer komplett im selben Frame angewendet */
    private final ConcurrentLinkedQueue<MeshBatch> uploadQueue = new ConcurrentLinkedQueue<>();

    /* Remesh-Batches (Edits/Fluid) - werden vom Renderer VOR der normalen Queue geleert,
       damit platzierte Blöcke nicht hinter dem Initial-Load warten. */
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
       Chunk.announceTickRestore): World.restorePendingScheduledTicks pollt nur diese Queue
       statt jeden Tick alle Chunks zu scannen (im Steady-State null Treffer). */
    private final ConcurrentLinkedQueue<Chunk> tickRestoreQueue = new ConcurrentLinkedQueue<>();

    /* Frisch READY gewordene Chunks (Announce vom Mesh-Worker). World.processReadyChunks holt
       sie ab, damit Blöcke mit transientem Vergleichs-Zustand ihre Basis wiederherstellen können
       — heute nur der Beobachter, dessen Map keinen Weltwechsel überlebt. Bewusst hier und nicht
       früher: das Gate des Mesh-Jobs garantiert alle 8 Nachbarn auf mindestens LIT, erst dann
       liest World.getBlock über die Chunk-Grenze echte Daten statt still Luft. */
    private final ConcurrentLinkedQueue<Chunk> readyAnnounceQueue = new ConcurrentLinkedQueue<>();

    /* Aktualitäts-Sequenz für Mesh-Ergebnisse, vergeben beim Job-SUBMIT (Render-Thread, seriell):
       ein später submitteter Job hat immer neuere Daten. Der Renderer verwirft in applyBatch
       Batches, deren Seq nicht neuer ist als das zuletzt angewendete Mesh der Section — sonst
       überschreibt ein Erst-Mesh aus der langen uploadQueue ein bereits angewendetes
       Priority-Remesh derselben Section (Dirty-Bit ist dann schon konsumiert → dauerhafte Naht). */
    private long nextMeshSeq = 1;

    private int renderDistance = 16; // in chunks

    /* Unload-Gate: sichtbare Chunks erst entladen, wenn das LOD ihre Zelle deckt. null = Gate aus. */
    private LodManager lodManager;

    /* Chunk-Persistenz: Lade-Quelle (statt Generierung) + Save-Ziel beim Unload. Von World
       nach der Konstruktion gesetzt; null-tolerant (Tools/Tests ohne Persistenz). */
    private WorldStorage storage;

    /* Debug: friert Laden/Generieren/Unload ein (Remeshes von Spieler-Edits laufen weiter).
       volatile nur der Sichtbarkeit halber — gelesen wird auf dem Tick-/Render-Thread. */
    private volatile boolean loadingPaused = false;

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

    /* Job-Prioritäten für den Worker-Pool: Remeshes überholen den Initial-Load;
       LOD-Meshes laufen nur, wenn keine Chunk-Arbeit ansteht. Ausnahme LOD_CLIP:
       Masken-Remeshes (Chunk wurde sichtbar bzw. geht in pendingUnload) überholen die
       Lade-Queue — hinter bis zu LOAD_QUEUE_LIMIT Lade-Jobs verhungert der Clip sonst
       beim Schnellflug: sichtbare Doppel-Geometrie (LOD über frischen L0-Chunks) in
       Lade-Richtung, festgehaltene pendingUnload-Meshes (Arena-Druck) in Unload-Richtung. */
    private static final int PRIO_REMESH = 0;
    private static final int PRIO_LOD_CLIP = 1;
    private static final int PRIO_LOAD = 2;
    private static final int PRIO_LOD = 3;

    private final AtomicLong taskSeq = new AtomicLong();

    /* AUSSTEHENDE Lade-Jobs (wartend + laufend) — Basis für LOAD_QUEUE_LIMIT und den Latch. */
    private final AtomicInteger pendingLoadTasks = new AtomicInteger();

    /* Lade-Jobs, die update() in DIESEM Tick eingereiht hat (nur Tick-Thread). */
    private int loadSubmitsThisTick;

    /* Latch: true, sobald die Lade-Pipeline einmal ihren FIXPUNKT erreicht hat — nichts mehr
       einzureihen UND nichts mehr ausstehend. Gate für das LOD: echte Chunks haben Vorrang,
       LOD-Jobs (beim Start teuer — ohne Chunks fällt die LodDataSource pro Zelle auf den
       Generator-Noise zurück) laufen erst danach.

       Fixpunkt statt "alle Chunks READY": die äußersten Ringe des Lade-Kreises finden ihre
       Gating-Nachbarn (Dekoration braucht 8× GENERATED, Erst-Mesh 8× DECORATED) außerhalb des
       Kreises NICHT und bleiben dauerhaft auf GENERATED/DECORATED stehen. Ein "alle READY"-Latch
       würde deshalb NIE auslösen und das LOD für immer abschalten (real beobachtet).

       Bewusst ein EINMALIGER Latch, kein Dauer-Gate: "solange irgendein Chunk lädt" würde im
       Betrieb permanent greifen (an der Ladefront ist immer etwas offen) → LOD-Regionen würden
       nie mehr remeshen. */
    private volatile boolean initialLoadComplete = false;

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
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(ChunkMesher::new);

    /* Dito für die Licht-Engine: eine Instanz ist NICHT threadsicher (BFS-Queues + 3x3-Kontext
       als Felder). World hält eine eigene für die Edit-Updates auf dem Render-Thread. */
    private final ThreadLocal<LightEngine> lightEngines = ThreadLocal.withInitial(LightEngine::new);

    public record MeshResult(int chunkX, int sectionY, int chunkZ, ChunkMesher.MeshData data, long meshSeq) {}

    public record MeshBatch(List<MeshResult> results) {}

    public ChunkManager(WorldGenerator generator, ChunkDecorator decorator) {
        this.generator = generator;
        this.decorator = decorator;

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        /* Prioritäts-Queue statt FIFO: Edit-Remeshes (PRIO_REMESH) überholen wartende
           Generierungs-/Erst-Mesh-Jobs (PRIO_LOAD), ohne einen eigenen Thread zu brauchen. */
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "Chunk Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    private void submitTask(int prio, Runnable job) {
        /* execute statt submit: submit() würde in ein nicht-vergleichbares FutureTask wrappen */
        this.workers.execute(new PrioTask(prio, this.taskSeq.getAndIncrement(), job));
    }

    /**
     * Lade-Job (Generierung/Dekoration/Erst-Mesh) mit Buchführung für {@link #LOAD_QUEUE_LIMIT}
     * und {@link #initialLoadComplete}. Dekrementiert wird am ENDE des Jobs — der Zähler steht
     * also für AUSSTEHENDE Arbeit (wartend + laufend), sonst könnte der Latch auslösen, während
     * Worker noch mitten in einem Chunk stecken.
     */
    private void submitLoadTask(Runnable job) {
        this.pendingLoadTasks.incrementAndGet();
        this.loadSubmitsThisTick++;
        this.submitTask(PRIO_LOAD, () -> {
            try {
                job.run();
            } finally {
                this.pendingLoadTasks.decrementAndGet();
            }
        });
    }

    /**
     * Reiht einen LOD-Mesh-Job ein. Normale Builds laufen mit niedrigster Priorität
     * (verdrängen nie Chunk-Jobs); Clip-Remeshes ({@code clip=true}, reiner Masken-Diff)
     * überholen die Lade-Queue (s. Kommentar an den PRIO_*-Konstanten).
     */
    public void submitLodTask(Runnable job, boolean clip) {
        this.submitTask(clip ? PRIO_LOD_CLIP : PRIO_LOD, job);
    }

    /**
     * true, sobald alle Chunks im Radius einmal READY waren (s. {@link #initialLoadComplete}).
     * Der {@link LodManager} submittet erst danach — echtes Terrain hat Vorrang.
     */
    public boolean isInitialLoadComplete() {
        return this.initialLoadComplete;
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
    private record PrioTask(int prio, long seq, Runnable job) implements Runnable, Comparable<PrioTask> {
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
                chunk.remeshQueue = this.remeshQueue; // Dirty-Markierungen melden sich hier an
                chunk.blockEntityAnnounceQueue = this.blockEntityAnnounceQueue;
                chunk.tickRestoreQueue = this.tickRestoreQueue;
                this.chunks.put(key, chunk);
            }

            if (chunk.status == ChunkStatus.NEW && generationSubmits < MAX_GENERATION_SUBMITS_PER_TICK) {
                generationSubmits++;
                chunk.status = ChunkStatus.GENERATING;
                Chunk finalChunk = chunk;
                this.submitLoadTask(() -> {
                    /* Persistenz zuerst: gespeicherte Chunks waren beim Save schon dekoriert
                       (nur READY-Chunks sind editierbar) -> DECORATED überspringt die
                       Doppel-Dekoration (gleiches Muster wie remeshAll). Befüllt wird vor dem
                       Status-Publish — exakt der Vertrag des Generators. */
                    if (this.storage != null && this.storage.loadChunk(finalChunk)) {
                        finalChunk.status = ChunkStatus.DECORATED;
                    } else {
                        this.generator.generate(finalChunk);
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
                this.submitLoadTask(() -> {
                    this.decorator.decorate(finalChunk);
                    finalChunk.status = ChunkStatus.DECORATED;
                });
            }

            /* 3. Initial-Lighting: Heightmap + Himmelslicht-Flood, sobald alle 8 Nachbarn
               dekoriert sind - der Job LIEST Blöcke über die Ränder (schreibt Licht aber nur
               ins Zentrum). submitLoadTask und nicht submitTask: sonst zählt der Job nicht in
               pendingLoadTasks, der initialLoadComplete-Fixpunkt feuert zu früh und das LOD
               startet auf halb belichtetem Terrain. */
            if (chunk.status == ChunkStatus.DECORATED) {
                Chunk north = this.getAtLeast(cx, cz - 1, ChunkStatus.DECORATED);
                Chunk south = this.getAtLeast(cx, cz + 1, ChunkStatus.DECORATED);
                Chunk west = this.getAtLeast(cx - 1, cz, ChunkStatus.DECORATED);
                Chunk east = this.getAtLeast(cx + 1, cz, ChunkStatus.DECORATED);
                Chunk[] diagonals = this.getDiagonalsAtLeast(cx, cz, ChunkStatus.DECORATED);
                if (north == null || south == null || west == null || east == null || diagonals == null) continue;

                chunk.status = ChunkStatus.LIGHTING;
                Chunk finalChunk = chunk;
                this.submitLoadTask(() -> {
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
                });
            }

            /* 4. Erst-Mesh: alle 16 Sections, sobald alle 8 Nachbarn belichtet sind - erst dann
               stehen die Feature-Scheiben an den Rändern fest (Diagonalen braucht zusätzlich
               die Fluid-Eckhöhen-Berechnung an Chunk-Ecken) UND das Nachbar-Licht steht, das
               der Mesher fürs Corner-Smoothing sampelt.
               Jede Section als eigener Batch, damit der Upload über Frames verteilt wird. */
            if (chunk.status == ChunkStatus.LIT) {
                Chunk north = this.getAtLeast(cx, cz - 1, ChunkStatus.LIT);
                Chunk south = this.getAtLeast(cx, cz + 1, ChunkStatus.LIT);
                Chunk west = this.getAtLeast(cx - 1, cz, ChunkStatus.LIT);
                Chunk east = this.getAtLeast(cx + 1, cz, ChunkStatus.LIT);
                Chunk[] diagonals = this.getDiagonalsAtLeast(cx, cz, ChunkStatus.LIT);
                if (north == null || south == null || west == null || east == null || diagonals == null) continue;

                chunk.status = ChunkStatus.MESHING;
                Chunk finalChunk = chunk;
                long meshSeq = this.nextMeshSeq++;
                this.submitLoadTask(() -> {
                    ChunkMesher mesher = this.meshers.get();
                    lockRead(finalChunk, north, south, west, east, diagonals);
                    try {
                        for (int s = 0; s < Chunk.SECTIONS; s++) {
                            ChunkMesher.MeshData mesh = mesher.mesh(finalChunk, s, north, south, west, east, diagonals);
                            this.uploadQueue.add(new MeshBatch(List.of(
                                    new MeshResult(finalChunk.chunkX, s, finalChunk.chunkZ, mesh, meshSeq))));
                        }
                    } finally {
                        unlockRead(finalChunk, north, south, west, east, diagonals);
                    }
                    finalChunk.status = ChunkStatus.READY;
                    this.readyAnnounceQueue.add(finalChunk);
                });
            }
        }

        /* Fixpunkt der Lade-Pipeline: nichts mehr einzureihen UND nichts mehr ausstehend →
           ab jetzt darf das LOD submitten. Nur setzen, nie löschen (s. initialLoadComplete). */
        if (!this.initialLoadComplete && this.loadSubmitsThisTick == 0 && this.pendingLoadTasks.get() == 0) {
            this.initialLoadComplete = true;
            this.logger.debug("Initialer Chunk-Load fertig — LOD-Jobs sind ab jetzt freigegeben");
        }

        /* 5. Unload chunks far outside the render distance.
           READY, LIT, DECORATED und GENERATED dürfen weg - nur laufende Jobs
           (GENERATING/DECORATING/LIGHTING/MESHING) bleiben, bis sie fertig sind,
           sonst arbeiten Worker auf entfernten Chunks. */
        int unloadDist = this.renderDistance + 2;
        /* Notventil: jenseits davon wird bedingungslos entladen — pendingUnload-Chunks können
           sich bei schnellem Flug nicht unbegrenzt ansammeln, wenn das LOD hinterherhinkt. */
        int hardDist = unloadDist + 4;
        Iterator<Map.Entry<Long, Chunk>> it = this.chunks.entrySet().iterator();
        while (it.hasNext()) {
            Chunk chunk = it.next().getValue();
            int dx = chunk.chunkX - pcx, dz = chunk.chunkZ - pcz;
            int d2 = dx * dx + dz * dz;
            if (d2 <= unloadDist * unloadDist) {
                chunk.pendingUnload = false; // wieder im Radius → Gate zurücksetzen
                continue;
            }

            ChunkStatus status = chunk.status;
            if (status == ChunkStatus.READY || status == ChunkStatus.LIT
                    || status == ChunkStatus.DECORATED
                    || status == ChunkStatus.GENERATED || status == ChunkStatus.NEW) {
                /* Save-Gate: modifizierte Chunks bleiben in der Map, bis der IO-Thread den
                   Save abgeschlossen hat (saveQueued-Protokoll). Löst zugleich die
                   Unload/Reload-Race — kommt der Spieler zurück, ist der Chunk noch da. */
                if (this.storage != null && (chunk.modified || chunk.saveQueued)) {
                    if (!chunk.saveQueued) {
                        chunk.materializeFallingBlocks();
                        chunk.saveQueued = true;
                        this.storage.enqueueSave(chunk);
                    }
                    continue;
                }
                /* Sichtbare Chunks (alle Sections auf dem Schirm) erst entfernen, wenn das
                   hochgeladene LOD-Mesh die Zelle deckt — sonst reißt ein Loch auf, bis der
                   Region-Remesh durch ist. Nicht (voll) hochgeladene Chunks waren nie in
                   einer LOD-Maske geclippt und dürfen sofort weg. */
                if (chunk.isFullyUploaded() && d2 <= hardDist * hardDist
                        && this.lodManager != null && !this.lodManager.coversChunk(chunk.chunkX, chunk.chunkZ)) {
                    chunk.pendingUnload = true; // computeMask behandelt ihn ab jetzt als abwesend
                    continue;
                }
                it.remove();
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
                if (chunk.modified && !chunk.saveQueued) {
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
        this.priorityUploadQueue.clear();
        this.remeshQueue.clear(); // alte Chunk-Objekte; Neuanlagen melden sich selbst wieder an
        this.blockEntityAnnounceQueue.clear();
        this.tickRestoreQueue.clear();
        this.readyAnnounceQueue.clear();
        this.chunksWithBlockEntities.clear();
        this.chunkRemovalVersion++;
        this.initialLoadComplete = false; // alles lädt neu → LOD wartet wieder auf das echte Terrain
    }

    public int getChunkRemovalVersion() {
        return chunkRemovalVersion;
    }

    /**
     * Einmal pro FRAME (z.B. aus World.render) aufrufen: stößt Remeshes
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
                /* Markierung vor READY (World.markDirty ab LIT, Licht-Randaustausch):
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

            int mask = chunk.consumeDirtySections();
            if (mask == 0) continue;

            long meshSeq = this.nextMeshSeq++;
            this.submitTask(PRIO_REMESH, () -> {
                ChunkMesher mesher = this.meshers.get();
                List<MeshResult> batch = new ArrayList<>(Integer.bitCount(mask));
                lockRead(chunk, north, south, west, east, diagonals);
                try {
                    for (int s = 0; s < Chunk.SECTIONS; s++) {
                        if ((mask & (1 << s)) == 0) continue;
                        batch.add(new MeshResult(chunk.chunkX, s, chunk.chunkZ,
                                mesher.mesh(chunk, s, north, south, west, east, diagonals), meshSeq));
                    }
                } finally {
                    unlockRead(chunk, north, south, west, east, diagonals);
                }
                /* Enqueue außerhalb des Locks ist ok: überholen sich zwei Remesh-Jobs derselben
                   Section hier, verwirft applyBatch den älteren über die meshSeq-Prüfung. */
                this.priorityUploadQueue.add(new MeshBatch(batch));
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

    public void requeueTickRestore(Chunk chunk) {
        this.tickRestoreQueue.add(chunk);
    }

    public java.util.Collection<Chunk> loadedChunks() {
        return this.chunks.values();
    }

    public ConcurrentLinkedQueue<MeshBatch> getUploadQueue() {
        return uploadQueue;
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
        /* Idempotent: unveränderter Wert darf initialLoadComplete nicht resetten (das Optionsmenü
           ruft applySettings auch für unabhängige Einstellungen auf und würde sonst das
           LOD-Gating jedes Mal neu anstoßen). */
        if (clamped == this.renderDistance) return;
        this.renderDistance = clamped;
        /* loadOrder wird beim nächsten update() automatisch neu berechnet */
        this.initialLoadComplete = false; // größerer Radius → erst die neuen Chunks, dann LOD
    }

    public void setLodManager(LodManager lodManager) {
        this.lodManager = lodManager;
    }

    /** Chunk-Persistenz-Anbindung (von World nach der Konstruktion gesetzt). */
    public void setStorage(WorldStorage storage) {
        this.storage = storage;
    }

    public void dispose() {
        this.workers.shutdownNow();
        /* Auf laufende Jobs warten: beim Welt-Austritt (Rückkehr ins Hauptmenü) dürfen keine
           Alt-Jobs mehr auf Chunk-/Generator-Daten arbeiten, wenn direkt danach eine neue Welt
           entsteht. Beim App-Exit ist das Warten ebenso korrekt (Jobs sind kurz). */
        try {
            if (!this.workers.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                this.logger.warning("Chunk-Worker haben nach 2 s nicht terminiert");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}