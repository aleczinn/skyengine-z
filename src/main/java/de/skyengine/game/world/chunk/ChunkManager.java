package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class ChunkManager {

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final WorldGenerator generator;

    /* Worker -> render thread: Ein Batch wird immer komplett im selben Frame angewendet */
    private final ConcurrentLinkedQueue<MeshBatch> uploadQueue = new ConcurrentLinkedQueue<>();

    private int renderDistance = 20; // in chunks

    /* One mesher per worker thread, reused (allocation-free) */
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(ChunkMesher::new);

    public record MeshResult(int chunkX, int sectionY, int chunkZ, float[] vertexData) {}

    public record MeshBatch(List<MeshResult> results) {}

    public ChunkManager(WorldGenerator generator) {
        this.generator = generator;

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Chunk Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /**
     * Einmal pro TICK: Chunks erzeugen, generieren, Erst-Mesh, Unload.
     */
    public void update(EntityPlayer player) {
        int pcx = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        int pcz = (int) Math.floor(player.z) >> ChunkSection.SHIFT;

        /* 1. Create + generate missing chunks in range */
        for (int dx = -this.renderDistance; dx <= this.renderDistance; dx++) {
            for (int dz = -this.renderDistance; dz <= this.renderDistance; dz++) {
                if (dx * dx + dz * dz > this.renderDistance * this.renderDistance) continue;

                int cx = pcx + dx, cz = pcz + dz;
                long key = Chunk.key(cx, cz);

                Chunk chunk = this.chunks.get(key);
                if (chunk == null) {
                    chunk = new Chunk(cx, cz);
                    this.chunks.put(key, chunk);
                }

                if (chunk.status == ChunkStatus.NEW) {
                    chunk.status = ChunkStatus.GENERATING;
                    Chunk finalChunk = chunk;
                    this.workers.submit(() -> {
                        this.generator.generate(finalChunk);
                        finalChunk.status = ChunkStatus.GENERATED;
                    });
                }

                /* 2. Erst-Mesh: alle 16 Sections, sobald Nachbarn generiert sind.
                   Jede Section als eigener Batch, damit der Upload über Frames verteilt wird. */
                if (chunk.status == ChunkStatus.GENERATED) {
                    Chunk north = this.getGenerated(cx, cz - 1);
                    Chunk south = this.getGenerated(cx, cz + 1);
                    Chunk west = this.getGenerated(cx - 1, cz);
                    Chunk east = this.getGenerated(cx + 1, cz);
                    if (north == null || south == null || west == null || east == null) continue;

                    chunk.status = ChunkStatus.MESHING;
                    Chunk finalChunk = chunk;
                    this.workers.submit(() -> {
                        ChunkMesher mesher = this.meshers.get();
                        for (int s = 0; s < Chunk.SECTIONS; s++) {
                            float[] mesh = mesher.mesh(finalChunk, s, north, south, west, east);
                            this.uploadQueue.add(new MeshBatch(List.of(
                                    new MeshResult(finalChunk.chunkX, s, finalChunk.chunkZ, mesh))));
                        }
                        finalChunk.status = ChunkStatus.READY;
                    });
                }
            }
        }

        /* 3. Unload chunks far outside the render distance */
        int unloadDist = this.renderDistance + 2;
        Iterator<Map.Entry<Long, Chunk>> it = this.chunks.entrySet().iterator();
        while (it.hasNext()) {
            Chunk chunk = it.next().getValue();
            int dx = chunk.chunkX - pcx, dz = chunk.chunkZ - pcz;
            if (dx * dx + dz * dz > unloadDist * unloadDist && (chunk.status == ChunkStatus.GENERATED || chunk.status == ChunkStatus.READY)) {
                it.remove();
                /* Renderer disposes the GL meshes when it notices the chunk is gone */
            }
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
            /* Nachbarn fehlen (Weltrand): Maske NICHT konsumieren, bleibt für später erhalten */
            if (north == null || south == null || west == null || east == null) continue;

            int mask = chunk.consumeDirtySections();
            if (mask == 0) continue;

            this.workers.submit(() -> {
                ChunkMesher mesher = this.meshers.get();
                List<MeshResult> batch = new ArrayList<>(Integer.bitCount(mask));
                for (int s = 0; s < Chunk.SECTIONS; s++) {
                    if ((mask & (1 << s)) == 0) continue;
                    batch.add(new MeshResult(chunk.chunkX, s, chunk.chunkZ,
                            mesher.mesh(chunk, s, north, south, west, east)));
                }
                this.uploadQueue.add(new MeshBatch(batch));
            });
        }
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

    public ConcurrentLinkedQueue<MeshBatch> getUploadQueue() {
        return uploadQueue;
    }

    public ConcurrentHashMap<Long, Chunk> getChunks() {
        return chunks;
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public void dispose() {
        this.workers.shutdownNow();
    }
}