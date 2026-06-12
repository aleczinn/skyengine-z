package de.skyengine.game.world.chunk;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;

public class ChunkManager {

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final WorldGenerator generator;

    /* Worker -> render thread: finished meshes waiting for GL upload */
    private final ConcurrentLinkedQueue<MeshResult> uploadQueue = new ConcurrentLinkedQueue<>();

    private int renderDistance = 20; // in chunks

    /* One mesher per worker thread, reused (allocation-free) */
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(ChunkMesher::new);

    public record MeshResult(int chunkX, int sectionY, int chunkZ, float[] vertexData) {
    }

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
     * Called once per tick from the render thread.
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

                /* 2a. Erst-Mesh: alle 16 Sections, sobald Nachbarn generiert sind */
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
                            this.uploadQueue.add(new MeshResult(finalChunk.chunkX, s, finalChunk.chunkZ, mesh));
                        }
                        finalChunk.status = ChunkStatus.READY;
                    });
                }

                /* 2b. Remesh: nur dirty Sections, Status bleibt READY */
                else if (chunk.status == ChunkStatus.READY && chunk.hasDirtySections()) {
                    Chunk north = this.getGenerated(cx, cz - 1);
                    Chunk south = this.getGenerated(cx, cz + 1);
                    Chunk west = this.getGenerated(cx - 1, cz);
                    Chunk east = this.getGenerated(cx + 1, cz);
                    if (north == null || south == null || west == null || east == null) continue;
                    // Maske wird hier bewusst NICHT konsumiert -> bleibt für später erhalten

                    int mask = chunk.consumeDirtySections();
                    if (mask == 0) continue;

                    Chunk finalChunk = chunk;
                    this.workers.submit(() -> {
                        ChunkMesher mesher = this.meshers.get();
                        for (int s = 0; s < Chunk.SECTIONS; s++) {
                            if ((mask & (1 << s)) == 0) continue;
                            float[] mesh = mesher.mesh(finalChunk, s, north, south, west, east);
                            this.uploadQueue.add(new MeshResult(finalChunk.chunkX, s, finalChunk.chunkZ, mesh));
                        }
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
            if (dx * dx + dz * dz > unloadDist * unloadDist && chunk.status == ChunkStatus.READY) {
                it.remove();
                /* Renderer disposes the GL meshes when it notices the chunk is gone */
            }
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

    public ConcurrentLinkedQueue<MeshResult> getUploadQueue() {
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