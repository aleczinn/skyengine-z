package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkManager {

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final WorldGenerator generator;

    /* Worker -> render thread: Ein Batch wird immer komplett im selben Frame angewendet */
    private final ConcurrentLinkedQueue<MeshBatch> uploadQueue = new ConcurrentLinkedQueue<>();

    /* Remesh-Batches (Edits/Fluid) - werden vom Renderer VOR der normalen Queue geleert,
       damit platzierte Blöcke nicht hinter dem Initial-Load warten. */
    private final ConcurrentLinkedQueue<MeshBatch> priorityUploadQueue = new ConcurrentLinkedQueue<>();

    private int renderDistance = 16; // in chunks

    /* Begrenzt, wie viele Generierungs-Jobs pro Tick submitted werden.
       Hält die Executor-Queue kurz -> nahe Chunks neuer Positionen kommen schnell dran. */
    private static final int MAX_GENERATION_SUBMITS_PER_TICK = 64;

    /* Job-Prioritäten für den Worker-Pool: Remeshes überholen den Initial-Load. */
    private static final int PRIO_REMESH = 0;
    private static final int PRIO_LOAD = 1;

    private final AtomicLong taskSeq = new AtomicLong();

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

    public record MeshResult(int chunkX, int sectionY, int chunkZ, ChunkMesher.MeshData data) {}

    public record MeshBatch(List<MeshResult> results) {}

    public ChunkManager(WorldGenerator generator) {
        this.generator = generator;

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        /* Prioritäts-Queue statt FIFO: Edit-Remeshes (PRIO_REMESH) überholen wartende
           Generierungs-/Erst-Mesh-Jobs (PRIO_LOAD), ohne einen eigenen Thread zu brauchen. */
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), r -> {
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
     * Einmal pro TICK: Chunks erzeugen, generieren, Erst-Mesh, Unload.
     * Reihenfolge: nah vor fern, in Blickrichtung vor dahinter (Score-Sort).
     */
    public void update(EntityPlayer player) {
        int pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        int pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;

        Offset[] order = this.getLoadOrder();

        /* Blickrichtungs-Priorisierung: score = dist * (1.5 - 0.5*cos(Winkel zur Blickrichtung))
           -> voraus zählt die Distanz 1x, seitlich 1.5x, hinten 2x. Nahe Chunks kommen damit
           immer früh dran, das Sichtfeld füllt sich aber zuerst. Neu sortiert wird nur bei
           Chunk-Wechsel oder >20° Drehung (wrap-sicher). */
        float yawDelta = Math.abs(((player.yaw - this.lastSortYaw + 540.0f) % 360.0f) - 180.0f);
        if (pcx != this.lastSortPcx || pcz != this.lastSortPcz || yawDelta > 20.0f) {
            this.lastSortPcx = pcx;
            this.lastSortPcz = pcz;
            this.lastSortYaw = player.yaw;

            double yawRad = Math.toRadians(player.yaw);
            float vx = (float) Math.sin(yawRad);
            float vz = (float) -Math.cos(yawRad);
            for (Offset o : order) {
                o.score = 1.5f * o.dist - 0.5f * (o.dx * vx + o.dz * vz);
            }
            Arrays.sort(order, Comparator.comparingDouble(o -> o.score));
        }

        int generationSubmits = 0;

        /* 1. Create + generate + Erst-Mesh, in Score-Reihenfolge */
        for (Offset offset : order) {
            int cx = pcx + offset.dx;
            int cz = pcz + offset.dz;
            long key = Chunk.key(cx, cz);

            Chunk chunk = this.chunks.get(key);
            if (chunk == null) {
                chunk = new Chunk(cx, cz);
                this.chunks.put(key, chunk);
            }

            if (chunk.status == ChunkStatus.NEW && generationSubmits < MAX_GENERATION_SUBMITS_PER_TICK) {
                generationSubmits++;
                chunk.status = ChunkStatus.GENERATING;
                Chunk finalChunk = chunk;
                this.submitTask(PRIO_LOAD, () -> {
                    this.generator.generate(finalChunk);
                    finalChunk.status = ChunkStatus.GENERATED;
                });
            }

            /* 2. Erst-Mesh: alle 16 Sections, sobald alle 8 Nachbarn generiert sind (Diagonalen
               braucht die Fluid-Eckhöhen-Berechnung an Chunk-Ecken).
               Jede Section als eigener Batch, damit der Upload über Frames verteilt wird. */
            if (chunk.status == ChunkStatus.GENERATED) {
                Chunk north = this.getGenerated(cx, cz - 1);
                Chunk south = this.getGenerated(cx, cz + 1);
                Chunk west = this.getGenerated(cx - 1, cz);
                Chunk east = this.getGenerated(cx + 1, cz);
                Chunk[] diagonals = this.getGeneratedDiagonals(cx, cz);
                if (north == null || south == null || west == null || east == null || diagonals == null) continue;

                chunk.status = ChunkStatus.MESHING;
                Chunk finalChunk = chunk;
                this.submitTask(PRIO_LOAD, () -> {
                    ChunkMesher mesher = this.meshers.get();
                    lockRead(finalChunk, north, south, west, east, diagonals);
                    try {
                        for (int s = 0; s < Chunk.SECTIONS; s++) {
                            ChunkMesher.MeshData mesh = mesher.mesh(finalChunk, s, north, south, west, east, diagonals);
                            this.uploadQueue.add(new MeshBatch(List.of(
                                    new MeshResult(finalChunk.chunkX, s, finalChunk.chunkZ, mesh))));
                        }
                    } finally {
                        unlockRead(finalChunk, north, south, west, east, diagonals);
                    }
                    finalChunk.status = ChunkStatus.READY;
                });
            }
        }

        /* 3. Unload chunks far outside the render distance.
           READY und GENERATED dürfen weg - nur laufende Jobs (GENERATING/MESHING)
           bleiben, bis sie fertig sind, sonst arbeiten Worker auf entfernten Chunks. */
        int unloadDist = this.renderDistance + 2;
        Iterator<Map.Entry<Long, Chunk>> it = this.chunks.entrySet().iterator();
        while (it.hasNext()) {
            Chunk chunk = it.next().getValue();
            int dx = chunk.chunkX - pcx, dz = chunk.chunkZ - pcz;
            if (dx * dx + dz * dz <= unloadDist * unloadDist) continue;

            ChunkStatus status = chunk.status;
            if (status == ChunkStatus.READY || status == ChunkStatus.GENERATED || status == ChunkStatus.NEW) {
                it.remove();
                /* Renderer disposes the GL meshes when it notices the chunk is gone */
            }
        }
    }

    /**
     * Setzt alle fertigen Chunks auf GENERATED zurück — der normale Lade-Pfad ({@link #update})
     * meshed sie dann progressiv neu (Blickrichtungs-Score, Upload-Budget). Für Settings, die
     * ins gebackene Mesh eingehen (z.B. Smooth Lighting): alte Meshes bleiben sichtbar, bis der
     * Ersatz hochgeladen ist. Chunks, die gerade GENERATING/MESHING sind, bleiben unberührt.
     */
    public void remeshAll() {
        for (Chunk chunk : this.chunks.values()) {
            if (chunk.status == ChunkStatus.READY) chunk.status = ChunkStatus.GENERATED;
        }
    }

    /**
     * Einmal pro FRAME (z.B. aus World.render) aufrufen: stößt Remeshes
     * für dirty Sections sofort an, statt auf den nächsten Tick zu warten.
     */
    public void processRemeshes() {
        for (Chunk chunk : this.chunks.values()) {
            if (chunk.status != ChunkStatus.READY || !chunk.hasDirtySections()) continue;

            Chunk north = this.getGenerated(chunk.chunkX, chunk.chunkZ - 1);
            Chunk south = this.getGenerated(chunk.chunkX, chunk.chunkZ + 1);
            Chunk west = this.getGenerated(chunk.chunkX - 1, chunk.chunkZ);
            Chunk east = this.getGenerated(chunk.chunkX + 1, chunk.chunkZ);
            Chunk[] diagonals = this.getGeneratedDiagonals(chunk.chunkX, chunk.chunkZ);
            /* Nachbarn fehlen (Weltrand): Maske NICHT konsumieren, bleibt für später erhalten */
            if (north == null || south == null || west == null || east == null || diagonals == null) continue;

            int mask = chunk.consumeDirtySections();
            if (mask == 0) continue;

            this.submitTask(PRIO_REMESH, () -> {
                ChunkMesher mesher = this.meshers.get();
                List<MeshResult> batch = new ArrayList<>(Integer.bitCount(mask));
                lockRead(chunk, north, south, west, east, diagonals);
                try {
                    for (int s = 0; s < Chunk.SECTIONS; s++) {
                        if ((mask & (1 << s)) == 0) continue;
                        batch.add(new MeshResult(chunk.chunkX, s, chunk.chunkZ,
                                mesher.mesh(chunk, s, north, south, west, east, diagonals)));
                    }
                } finally {
                    unlockRead(chunk, north, south, west, east, diagonals);
                }
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
     * sie erwartet), oder null, wenn einer noch nicht generiert ist.
     */
    private Chunk[] getGeneratedDiagonals(int cx, int cz) {
        Chunk nw = this.getGenerated(cx - 1, cz - 1);
        Chunk ne = this.getGenerated(cx + 1, cz - 1);
        Chunk sw = this.getGenerated(cx - 1, cz + 1);
        Chunk se = this.getGenerated(cx + 1, cz + 1);
        if (nw == null || ne == null || sw == null || se == null) return null;
        return new Chunk[]{nw, ne, sw, se};
    }

    private Chunk getGenerated(int cx, int cz) {
        Chunk chunk = this.chunks.get(Chunk.key(cx, cz));
        if (chunk == null) return null;
        ChunkStatus status = chunk.status;
        return (status == ChunkStatus.GENERATED || status == ChunkStatus.MESHING || status == ChunkStatus.READY) ? chunk : null;
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return this.chunks.get(Chunk.key(chunkX, chunkZ));
    }

    /** Alle aktuell geladenen Chunks (z.B. fürs BlockEntity-Ticking). */
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
        this.renderDistance = Math.max(2, renderDistance);
        /* loadOrder wird beim nächsten update() automatisch neu berechnet */
    }

    public void dispose() {
        this.workers.shutdownNow();
    }
}