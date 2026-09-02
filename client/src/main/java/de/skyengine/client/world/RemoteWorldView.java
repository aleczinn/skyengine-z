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
    private boolean closed;
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
        this.dimension = java.util.Objects.requireNonNull(dimension);
        this.cache = java.util.Objects.requireNonNull(cache);
        this.blockEntityRenderers = blockEntityRenderers;
        this.environment = environment(dimension);
        this.chunks = new ChunkManager(null, null, this.environment.hasSkylight());
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
            this.cache.setListener(adapter);
            for (var snapshot : this.cache.snapshots()) adapter.chunkLoaded(snapshot);
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
        FrameProfiler.cpuStart(FrameProfiler.Cpu.REMESH);
        this.chunks.processRemeshes();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.REMESH);
        this.renderer.renderSolid(camera);
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
    public String dimension() { return this.dimension; }
    public boolean hasRenderableChunks() {
        for (var chunk : this.chunks.loadedChunks()) {
            if (chunk.status == ChunkStatus.READY) return true;
        }
        return false;
    }
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
        this.physicsDimension.setReplicatedPhysicsPlayer(player);
    }
    public boolean isPhysicsAreaReady(double x, double z) {
        int centerX = (int) Math.floor(x) >> ChunkSection.SHIFT;
        int centerZ = (int) Math.floor(z) >> ChunkSection.SHIFT;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk chunk = this.chunks.getChunk(centerX + dx, centerZ + dz);
                if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return false;
            }
        }
        return true;
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
}
