package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature-Pass (Dekoration) nach der Terrain-Generierung, Scheiben-Modell: Der Ziel-Chunk
 * wertet die Features aller 9 Chunks seines 3×3-Umfelds deterministisch aus, schreibt aber
 * nur Blöcke, die in ihm selbst liegen. Ein Baum an der Grenze wird von jedem geschnittenen
 * Chunk identisch neu berechnet — keine Cross-Chunk-Writes, nichts geht beim
 * Unload/Regenerieren verloren.
 *
 * <p>Hält bewusst KEINE Referenz auf ChunkManager/Dimension: Dekorieren kann konstruktionsbedingt
 * keine Nachbar-Generierung anstoßen (keine Kaskaden/Deadlocks im Worker-Pool — der
 * ChunkManager wartet, nie der Job).
 */
public final class ChunkDecorator {

    /** Lazily materialisierte, deterministische Feature-Projektion fuer ein Chunk-Rechteck. */
    public final class LodRegionFeatures {
        private final int minChunkX, minChunkZ, width, height;
        private LodFeatureBuffer[] buffers;

        private LodRegionFeatures(int minChunkX, int minChunkZ, int width, int height) {
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.width = width;
            this.height = height;
        }

        public synchronized LodFeatureBuffer forChunk(int chunkX, int chunkZ) {
            if (chunkX < this.minChunkX || chunkX >= this.minChunkX + this.width
                    || chunkZ < this.minChunkZ || chunkZ >= this.minChunkZ + this.height) {
                return ChunkDecorator.this.decorateForLod(chunkX, chunkZ);
            }
            if (this.buffers == null) this.materialize();
            return this.buffers[(chunkZ - this.minChunkZ) * this.width + chunkX - this.minChunkX];
        }

        private void materialize() {
            this.buffers = new LodFeatureBuffer[this.width * this.height];
            for (int z = 0; z < this.height; z++) {
                for (int x = 0; x < this.width; x++) {
                    this.buffers[z * this.width + x] = new LodFeatureBuffer(
                            this.minChunkX + x, this.minChunkZ + z, ChunkDecorator.this.generator);
                }
            }
            for (int sx = this.minChunkX - 1; sx <= this.minChunkX + this.width; sx++) {
                for (int sz = this.minChunkZ - 1; sz <= this.minChunkZ + this.height; sz++) {
                    LodFeatureTile tile = ChunkDecorator.this.lodFeatureTile(sx, sz);
                    for (int i = 0; i < tile.size(); i++) {
                        int targetX = tile.worldX(i) >> ChunkSection.SHIFT;
                        int targetZ = tile.worldZ(i) >> ChunkSection.SHIFT;
                        if (targetX < this.minChunkX || targetX >= this.minChunkX + this.width
                                || targetZ < this.minChunkZ || targetZ >= this.minChunkZ + this.height) continue;
                        this.buffers[(targetZ - this.minChunkZ) * this.width
                                + targetX - this.minChunkX].apply(tile, i);
                    }
                    for (int i = 0; i < tile.supportSize(); i++) {
                        int targetX = tile.supportWorldX(i) >> ChunkSection.SHIFT;
                        int targetZ = tile.supportWorldZ(i) >> ChunkSection.SHIFT;
                        if (targetX < this.minChunkX || targetX >= this.minChunkX + this.width
                                || targetZ < this.minChunkZ || targetZ >= this.minChunkZ + this.height) continue;
                        this.buffers[(targetZ - this.minChunkZ) * this.width
                                + targetX - this.minChunkX].applySupport(tile, i);
                    }
                }
            }
        }
    }

    private static final long LOD_TILE_CACHE_BYTES = Math.clamp(
            Runtime.getRuntime().maxMemory() / 32, 32L << 20, 128L << 20);

    private final WorldGenerator generator;
    /* Listen-Index = featureId (geht in den Seed ein) — Reihenfolge nie umsortieren! */
    private final List<Feature> features;
    private final Map<Long, LodFeatureTile> lodTileCache = new LinkedHashMap<>(256, 0.75f, true);
    private final ConcurrentHashMap<Long, CompletableFuture<LodFeatureTile>> lodTileBuilds =
            new ConcurrentHashMap<>();
    private long lodTileCacheBytes;

    public ChunkDecorator(WorldGenerator generator, List<Feature> features) {
        this.generator = generator;
        this.features = List.copyOf(features);
    }

    /**
     * Dekoriert den Ziel-Chunk. Feste Reihenfolge (row-major über die 3×3-Quellen, dann
     * Feature-Index), damit jede Zelle bei Schreib-Filtern ({@code setIfAir}) einen
     * deterministischen Vorzustand sieht.
     */
    public void decorate(Chunk target) {
        for (int sx = target.chunkX - 1; sx <= target.chunkX + 1; sx++) {
            for (int sz = target.chunkZ - 1; sz <= target.chunkZ + 1; sz++) {
                for (int i = 0; i < this.features.size(); i++) {
                    Random rng = new Random(featureSeed(this.generator.getSeed(), sx, sz, i));
                    this.features.get(i).place(new FeaturePlacer(target, sx, sz, rng, this.generator));
                }
            }
        }
    }

    /** Erzeugt denselben Feature-Pass für kurzlebige LOD-Scratch-Chunks. */
    public void decorateForLod(Chunk target) {
        this.decorate(target);
    }

    /** Derselbe Feature-Pass wie fuer echte Chunks, aber ohne Voxel-Sections und Vollscan. */
    public LodFeatureBuffer decorateForLod(int targetChunkX, int targetChunkZ) {
        LodFeatureBuffer target = new LodFeatureBuffer(targetChunkX, targetChunkZ, this.generator);
        for (int sx = targetChunkX - 1; sx <= targetChunkX + 1; sx++) {
            for (int sz = targetChunkZ - 1; sz <= targetChunkZ + 1; sz++) {
                target.apply(this.lodFeatureTile(sx, sz));
            }
        }
        return target;
    }

    public LodRegionFeatures lodRegion(int minChunkX, int minChunkZ, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Leere LOD-Feature-Region");
        return new LodRegionFeatures(minChunkX, minChunkZ, width, height);
    }

    /** Verwirft ausschließlich den kurzlebigen LOD-Feature-Cache, nicht echte Chunkdaten. */
    public void clearLodFeatureCache() {
        synchronized (this.lodTileCache) {
            this.lodTileCache.clear();
            this.lodTileCacheBytes = 0;
        }
        this.lodTileBuilds.clear();
    }

    private LodFeatureTile lodFeatureTile(int sourceChunkX, int sourceChunkZ) {
        long key = Chunk.key(sourceChunkX, sourceChunkZ);
        synchronized (this.lodTileCache) {
            LodFeatureTile cached = this.lodTileCache.get(key);
            if (cached != null) return cached;
        }

        CompletableFuture<LodFeatureTile> created = new CompletableFuture<>();
        CompletableFuture<LodFeatureTile> existing = this.lodTileBuilds.putIfAbsent(key, created);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException exception) {
                throw unwrap(exception);
            }
        }
        try {
            LodFeatureTile tile = this.buildLodFeatureTile(sourceChunkX, sourceChunkZ);
            synchronized (this.lodTileCache) {
                this.lodTileCache.put(key, tile);
                this.lodTileCacheBytes += tile.estimatedBytes();
                while (this.lodTileCacheBytes > LOD_TILE_CACHE_BYTES && this.lodTileCache.size() > 1) {
                    Map.Entry<Long, LodFeatureTile> eldest = this.lodTileCache.entrySet().iterator().next();
                    this.lodTileCacheBytes -= eldest.getValue().estimatedBytes();
                    this.lodTileCache.remove(eldest.getKey());
                }
            }
            created.complete(tile);
            return tile;
        } catch (RuntimeException exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            this.lodTileBuilds.remove(key, created);
        }
    }

    private LodFeatureTile buildLodFeatureTile(int sourceChunkX, int sourceChunkZ) {
        LodFeatureTile tile = new LodFeatureTile(sourceChunkX, sourceChunkZ, this.generator);
        for (int i = 0; i < this.features.size(); i++) {
            tile.begin(new Random(featureSeed(this.generator.getSeed(), sourceChunkX, sourceChunkZ, i)));
            this.features.get(i).place(tile);
        }
        tile.freeze();
        return tile;
    }

    private static RuntimeException unwrap(CompletionException exception) {
        return exception.getCause() instanceof RuntimeException runtime ? runtime : exception;
    }

    public int cacheFingerprint() {
        int hash = 1;
        for (Feature feature : this.features) {
            hash = 31 * hash + feature.getClass().getName().hashCode();
            hash = 31 * hash + feature.cacheVersion();
        }
        return hash;
    }

    /**
     * Seed pro (Quell-Chunk, Feature): worldSeed XOR gepackte Chunk-Koordinaten XOR featureId,
     * durch den SplitMix64-Finalizer gemischt — benachbarte Chunks bekommen unkorrelierte Seeds.
     */
    private static long featureSeed(int worldSeed, int sx, int sz, int featureId) {
        long s = (long) worldSeed ^ Chunk.key(sx, sz) ^ ((long) featureId << 17);
        s = (s ^ (s >>> 30)) * 0xBF58476D1CE4E5B9L;
        s = (s ^ (s >>> 27)) * 0x94D049BB133111EBL;
        return s ^ (s >>> 31);
    }
}
