package de.skyengine.graphics.world;

import de.skyengine.core.io.IDisposable;
import de.skyengine.game.world.Dimension;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.entity.EntityRenderer;
import de.skyengine.graphics.particle.ParticleRenderer;
import de.skyengine.graphics.texture.BlockTextureAtlas;

/** GPU-seitige Ansicht genau einer aktiven Dimension; besitzt keinerlei Savegame-Zustand. */
public final class DimensionView implements IDisposable {

    private final Dimension dimension;
    private final BlockEntityRenderDispatcher blockEntityRenderers;
    private final BlockTextureAtlas atlas;
    private final long renderGeneration;
    private final ChunkRenderer chunks;
    private final EntityRenderer entities = new EntityRenderer();
    private final ParticleRenderer particles;
    private final EnergyCableFlowRenderer cableFlow;
    private boolean disposed;

    public DimensionView(Dimension dimension, BlockTextureAtlas atlas,
                         BlockEntityRenderDispatcher blockEntityRenderers) {
        this.dimension = dimension;
        this.atlas = atlas;
        this.blockEntityRenderers = blockEntityRenderers;
        this.renderGeneration = dimension.getChunkManager().attachRenderer();
        try {
            this.chunks = new ChunkRenderer(dimension.getChunkManager(), this.renderGeneration);
            this.chunks.setEnvironment(dimension.getEnvironment());
            this.chunks.init(atlas);
            this.entities.init(atlas.textures());
            this.particles = new ParticleRenderer(dimension.particles(), atlas.textures());
            this.particles.init();
            this.cableFlow = new EnergyCableFlowRenderer(dimension.getEnergyNetworks());
            this.cableFlow.init();
        } catch (RuntimeException | Error failure) {
            dimension.getChunkManager().detachRenderer(this.renderGeneration);
            throw failure;
        }
    }

    public Dimension dimension() {
        return this.dimension;
    }

    public ChunkRenderer chunks() {
        return this.chunks;
    }

    public void reloadEntityRenderers() {
        this.entities.dispose();
        this.entities.init(this.atlas.textures());
        this.particles.dispose();
        this.particles.init();
    }

    public void render(Camera camera, float partialTick) {
        this.render(camera, partialTick, null);
    }

    public void render(Camera camera, float partialTick, Runnable beforeTranslucent) {
        FrameProfiler.cpuStart(FrameProfiler.Cpu.REMESH);
        this.dimension.getChunkManager().processRemeshes();
        FrameProfiler.cpuStop(FrameProfiler.Cpu.REMESH);
        this.chunks.renderSolid(camera);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.BE);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.BLOCK_ENTITIES);
        this.blockEntityRenderers.render(this.dimension.getChunkManager(), camera,
                partialTick, this.dimension.getEnvironment().ambientLight());
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.BLOCK_ENTITIES);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.BE);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.ENT);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.ENTITIES);
        this.entities.render(this.dimension, this.dimension.entityChunks(), camera, partialTick);
        if (beforeTranslucent != null) beforeTranslucent.run();
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.ENTITIES);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.ENT);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.PARTICLES);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.PARTICLES_OPAQUE);
        this.particles.renderOpaque(camera, partialTick);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.PARTICLES_OPAQUE);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.PARTICLES);
        this.chunks.renderTranslucent(camera);
        this.cableFlow.render(camera);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.PARTICLES);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.PARTICLES_TRANSLUCENT);
        this.particles.renderTranslucent(camera, partialTick);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.PARTICLES_TRANSLUCENT);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.PARTICLES);
    }

    @Override
    public void dispose() {
        if (this.disposed) return;
        this.disposed = true;
        this.dimension.getChunkManager().awaitWorkerTasks();
        this.dimension.getChunkManager().detachRenderer(this.renderGeneration);
        this.entities.dispose();
        this.particles.dispose();
        this.chunks.dispose();
        this.cableFlow.dispose();
    }
}
