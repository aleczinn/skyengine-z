package de.skyengine.client.world;

import de.skyengine.client.network.ReplicatedChunkCache;
import de.skyengine.client.network.ReplicatedChunkWorldAdapter;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.dimension.DimensionEnvironment;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.block.BlockRaycast;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.EnchantingTableBlockEntity;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.world.ChunkRenderer;
import de.skyengine.graphics.entity.EntityRenderer;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.particle.ParticleRenderer;
import de.skyengine.game.world.particle.ParticleEngine;

import java.util.List;

/** GL-owned terrain-only view of the authoritative replicated L0 world. */
public final class RemoteWorldView implements AutoCloseable {
    private final String dimension;
    private final ReplicatedChunkCache cache;
    private final DimensionEnvironment environment;
    private final ChunkManager chunks;
    private final Dimension physicsDimension;
    private final long renderGeneration;
    private final ChunkRenderer renderer;
    private final EntityRenderer entityRenderer = new EntityRenderer();
    private final BlockEntityRenderDispatcher blockEntityRenderers;
    private final ParticleEngine particleEngine;
    private final ParticleRenderer particleRenderer;
    private final ReplicatedChunkWorldAdapter replicatedChunks;
    private EntityPlayer physicsPlayer;
    private boolean closed;
    private int watchdogPresentationRadius = Integer.MIN_VALUE;
    private long watchdogStalledSinceNanos;
    private long watchdogLastResyncNanos;
    private final BlockRaycast.BlockAccess blockAccess = new BlockRaycast.BlockAccess() {
        @Override public int getBlock(int x, int y, int z) {
            if (y < 0 || y >= Chunk.HEIGHT) return Blocks.AIR;
            var chunk = chunks.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
            return chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT) ? Blocks.AIR
                    : chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
        }

        @Override public boolean isInteractionReady(int x, int y, int z) {
            if (y < 0 || y >= Chunk.HEIGHT) return false;
            var chunk = chunks.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
            return chunk != null && chunk.status == ChunkStatus.READY && chunk.isFullyUploaded();
        }
    };

    public RemoteWorldView(String dimension, ReplicatedChunkCache cache, BlockTextureAtlas atlas) {
        this(dimension, cache, atlas, null);
    }

    public RemoteWorldView(String dimension, ReplicatedChunkCache cache, BlockTextureAtlas atlas,
                           BlockEntityRenderDispatcher blockEntityRenderers) {
        this(dimension, cache, atlas, blockEntityRenderers, null);
    }

    public RemoteWorldView(String dimension, ReplicatedChunkCache cache, BlockTextureAtlas atlas,
                           BlockEntityRenderDispatcher blockEntityRenderers,
                           WorldWorkerPool sharedWorkers) {
        this.dimension = java.util.Objects.requireNonNull(dimension);
        this.cache = java.util.Objects.requireNonNull(cache);
        this.blockEntityRenderers = blockEntityRenderers;
        this.environment = environment(dimension);
        this.chunks = sharedWorkers == null
                ? new ChunkManager(null, null, clientChunkWorkers(), this.environment.hasSkylight())
                : new ChunkManager(null, null, sharedWorkers, this.environment.hasSkylight());
        this.physicsDimension = Dimension.replicatedClientView(
                Identifier.of(dimension), this.environment, this.chunks);
        this.particleEngine = new ParticleEngine(this.physicsDimension);
        this.physicsDimension.setParticleSink(this.particleEngine);
        this.particleRenderer = new ParticleRenderer(this.particleEngine, atlas.textures());
        this.renderGeneration = this.chunks.attachRenderer();
        ChunkRenderer created = new ChunkRenderer(this.chunks, this.renderGeneration);
        try {
            created.setEnvironment(this.environment);
            created.init(atlas);
            this.entityRenderer.init(atlas.textures());
            this.particleRenderer.init();
            ReplicatedChunkWorldAdapter adapter = new ReplicatedChunkWorldAdapter(
                    dimension, this.chunks, this.physicsDimension);
            this.replicatedChunks = adapter;
            this.cache.setListener(adapter);
            for (var snapshot : this.cache.snapshots()) adapter.chunkLoadedAsync(snapshot);
            this.renderer = created;
        } catch (RuntimeException | Error failure) {
            this.cache.setListener(null);
            this.chunks.detachRenderer(this.renderGeneration);
            this.particleRenderer.dispose();
            this.entityRenderer.dispose();
            created.dispose();
            this.chunks.dispose();
            throw failure;
        }
    }

    public void render(Camera camera) {
        render(camera, null);
    }

    public void render(Camera camera, Runnable entityPass) {
        render(camera, entityPass, List.of(), 0F);
    }

    public void render(Camera camera, Runnable playerPass, List<? extends Entity> entities,
                       float partialTick) {
        this.updatePreparedChunks();
        if (this.physicsPlayer != null) {
            this.chunks.setReplicatedRenderAnchor(
                    (int) Math.floor(this.physicsPlayer.x) >> ChunkSection.SHIFT,
                    (int) Math.floor(this.physicsPlayer.z) >> ChunkSection.SHIFT);
        }
        FrameProfiler.cpuStart(FrameProfiler.Cpu.REMESH);
        this.chunks.processRemeshes();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.REMESH);
        this.renderer.renderSolid(camera);
        this.maintainClosedPresentationFront();
        if (this.blockEntityRenderers != null) {
            this.blockEntityRenderers.render(this.chunks, camera, partialTick,
                    this.environment.ambientLight());
        }
        this.entityRenderer.renderEntities(this.physicsDimension, entities, camera, partialTick);
        if (playerPass != null) playerPass.run();
        this.particleRenderer.renderOpaque(camera, partialTick);
        this.renderer.renderTranslucent(camera);
        this.particleRenderer.renderTranslucent(camera, partialTick);
    }

    public int loadedChunks() { return this.chunks.getChunks().size(); }
    public void setRenderDistance(int chunks) { this.chunks.setRenderDistance(chunks); }
    public void updatePreparedChunks() { this.replicatedChunks.drainPreparedChunks(); }
    public String dimension() { return this.dimension; }
    public ChunkRenderer chunks() { return this.renderer; }
    public DimensionEnvironment environment() { return this.environment; }
    public BlockRaycast.BlockAccess blockAccess() { return this.blockAccess; }
    public Dimension physicsDimension() { return this.physicsDimension; }
    public void tickVisualEffects() {
        this.particleEngine.tick();
        // Only client-visual block entities tick in the mirror. Furnaces, hoppers and machines
        // remain exclusively server-authoritative and arrive through replication.
        for (Chunk chunk : this.chunks.chunksWithBlockEntities()) {
            if (chunk.status != ChunkStatus.READY) continue;
            for (var entity : chunk.blockEntities()) {
                if (entity instanceof ChestBlockEntity || entity instanceof EnchantingTableBlockEntity) {
                    entity.tick();
                }
            }
        }
    }
    public void setPhysicsPlayer(EntityPlayer player) {
        this.physicsPlayer = player;
        this.physicsDimension.setReplicatedPhysicsPlayer(player);
        if (player != null) {
            this.chunks.setReplicatedRenderAnchor(
                    (int) Math.floor(player.x) >> ChunkSection.SHIFT,
                    (int) Math.floor(player.z) >> ChunkSection.SHIFT);
        }
    }
    public void setAuthoritativeChunkListener(java.util.function.BiConsumer<Integer, Integer> listener) {
        this.replicatedChunks.setAuthoritativeUpdateListener(listener);
    }
    public boolean isPhysicsAreaReady(double x, double z) {
        return isAreaReady(x, z, false, 2);
    }

    public boolean isPhysicsChunkReady(int chunkX, int chunkZ) {
        Chunk chunk = this.chunks.getChunk(chunkX, chunkZ);
        return chunk != null && chunk.status.isAtLeast(ChunkStatus.LIT);
    }

    /** Spawn gate: the game is shown only after collision data is present and its meshes uploaded. */
    public boolean isInitialAreaReady(double x, double z) {
        return isAreaReady(x, z, true, 1);
    }

    private boolean isAreaReady(double x, double z, boolean requireUploadedMesh, int radius) {
        int centerX = (int) Math.floor(x) >> ChunkSection.SHIFT;
        int centerZ = (int) Math.floor(z) >> ChunkSection.SHIFT;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                // The two-chunk physics frontier mirrors circular server interest. The initial
                // one-chunk render gate intentionally remains a complete 3x3 including corners.
                if (radius > 1 && dx * dx + dz * dz > radius * radius) continue;
                Chunk chunk = this.chunks.getChunk(centerX + dx, centerZ + dz);
                if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return false;
                if (requireUploadedMesh
                        && (chunk.status != ChunkStatus.READY || !chunk.isFullyUploaded())) return false;
            }
        }
        return true;
    }

    /**
     * A TCP connection should not lose data, but an obsolete/cancelled async ticket must not be
     * able to leave the closed front stuck forever. After five seconds without radius progress,
     * rescan local mesh eligibility and request one nearest genuinely missing source column.
     */
    private void maintainClosedPresentationFront() {
        if (this.physicsPlayer == null) return;
        int radius = this.chunks.replicatedPresentationRadius();
        int target = this.chunks.getRenderDistance();
        long now = System.nanoTime();
        if (radius >= target) {
            this.watchdogPresentationRadius = radius;
            this.watchdogStalledSinceNanos = now;
            return;
        }
        if (radius != this.watchdogPresentationRadius) {
            this.watchdogPresentationRadius = radius;
            this.watchdogStalledSinceNanos = now;
            return;
        }
        if (now - this.watchdogStalledSinceNanos < 5_000_000_000L
                || now - this.watchdogLastResyncNanos < 500_000_000L) return;
        this.watchdogLastResyncNanos = now;
        this.chunks.rescanReplicatedMeshCandidates();

        int centerX = (int) Math.floor(this.physicsPlayer.x) >> ChunkSection.SHIFT;
        int centerZ = (int) Math.floor(this.physicsPlayer.z) >> ChunkSection.SHIFT;
        int extent = target + 1;
        int nearestX = 0, nearestZ = 0, nearestDistance = Integer.MAX_VALUE;
        for (int dz = -extent; dz <= extent; dz++) {
            for (int dx = -extent; dx <= extent; dx++) {
                int visibleDx = Math.max(0, Math.abs(dx) - 1);
                int visibleDz = Math.max(0, Math.abs(dz) - 1);
                if (visibleDx * visibleDx + visibleDz * visibleDz > target * target) continue;
                if (this.chunks.getChunk(centerX + dx, centerZ + dz) != null) continue;
                int distance = dx * dx + dz * dz;
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestX = centerX + dx;
                    nearestZ = centerZ + dz;
                }
            }
        }
        if (nearestDistance != Integer.MAX_VALUE) {
            this.cache.requestResyncNow(this.dimension, nearestX, nearestZ);
            this.watchdogStalledSinceNanos = now;
        }
    }

    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        this.cache.setListener(null);
        this.chunks.awaitWorkerTasks();
        this.chunks.detachRenderer(this.renderGeneration);
        this.renderer.dispose();
        this.entityRenderer.dispose();
        this.particleRenderer.dispose();
        this.chunks.dispose();
    }

    private static DimensionEnvironment environment(String dimension) {
        if (dimension != null) {
            try {
                var definition = WorldgenRegistries.DIMENSIONS.get(Identifier.of(dimension));
                if (definition != null) return definition.environment();
            } catch (IllegalArgumentException ignored) {
                // A malformed dimension will be rejected by the protocol/session as well. Keep
                // construction defensive so cleanup paths do not fail with a second exception.
            }
        }
        return DimensionEnvironment.OVERWORLD;
    }

    private static int clientChunkWorkers() {
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.min(8, Math.max(2, processors / 4));
    }
}
