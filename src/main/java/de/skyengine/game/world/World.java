package de.skyengine.game.world;

import de.skyengine.game.world.effect.WorldSoundSink;
import de.skyengine.core.io.IDisposable;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.game.world.dimension.PortalLinks;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.game.world.structure.StructureTemplateManager;
import de.skyengine.game.world.structure.WorldEditService;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/** Laufzeit eines geöffneten Savegames; besitzt Dimensionen und weltweite Dienste. */
public final class World implements IDisposable {

    private final WorldSaves.WorldSave save;
    private final File root;
    private final WorldWorkerPool workers;
    private final boolean ownsWorkers;
    private final PortalLinks portalLinks;
    private final PlayerManager players;
    private final DimensionManager dimensions;
    private final StructureTemplateManager structures;
    private final WorldEditService worldEdit;

    public World(WorldSaves.WorldSave save, WorldSoundSink soundManager) {
        this(save, WorldSaves.dir(save.dirName()), soundManager);
    }

    public World(WorldSaves.WorldSave save, File root, WorldSoundSink soundManager) {
        this(save, root, soundManager, true);
    }

    /** Dedicated servers use the same world without manufacturing a process-local player. */
    public World(WorldSaves.WorldSave save, File root, WorldSoundSink soundManager,
                 boolean createLocalPlayer) {
        this(save, root, soundManager, createLocalPlayer,
                Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
    }

    /** Server worlds pass their configured worker budget instead of silently using the host default. */
    public World(WorldSaves.WorldSave save, File root, WorldSoundSink soundManager,
                 boolean createLocalPlayer, int workerThreads) {
        this(save, root, soundManager, createLocalPlayer,
                new WorldWorkerPool(Math.max(1, workerThreads)), true);
    }

    /** Integrated Server und Client duerfen denselben CPU-Pool verwenden. */
    public World(WorldSaves.WorldSave save, File root, WorldSoundSink soundManager,
                 boolean createLocalPlayer, WorldWorkerPool workers) {
        this(save, root, soundManager, createLocalPlayer, workers, false);
    }

    private World(WorldSaves.WorldSave save, File root, WorldSoundSink soundManager,
                  boolean createLocalPlayer, WorldWorkerPool workers, boolean ownsWorkers) {
        this.save = save;
        this.root = root;
        this.workers = java.util.Objects.requireNonNull(workers, "workers");
        this.ownsWorkers = ownsWorkers;
        this.portalLinks = new PortalLinks(this.root);
        this.players = new PlayerManager(save, this.root,
                () -> WorldSaves.saveInDirectory(save, this.root), createLocalPlayer);
        this.structures = new StructureTemplateManager();
        this.worldEdit = new WorldEditService(this.structures);
        this.dimensions = new DimensionManager(save.dirName(), save.level(), this.root, this.workers, this.portalLinks, soundManager, structureSnapshot());
    }

    private StructureTemplateManager.Snapshot structureSnapshot() {
        try {
            return this.structures.snapshot();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Structure-Snapshot fuer Worldgen konnte nicht geladen werden", e);
        }
    }

    public WorldSaves.WorldSave saveDescriptor() {
        return this.save;
    }

    public File root() {
        return this.root;
    }

    public DimensionManager dimensions() {
        return this.dimensions;
    }

    public PortalLinks portalLinks() {
        return this.portalLinks;
    }

    public PlayerManager players() {
        return this.players;
    }

    public StructureTemplateManager structures() { return this.structures; }

    public WorldEditService worldEdit() { return this.worldEdit; }

    public record SpawnPoint(Identifier dimension, int x, int y, int z, float yaw, float pitch) {}

    /** null bedeutet den historischen Defaultspawn: Overworld 0/0 mit Generatorhoehe. */
    public SpawnPoint spawnPoint() {
        var level = this.save.level();
        if (level.spawnX == null || level.spawnY == null || level.spawnZ == null) return null;
        Identifier dimension = Identifier.of(level.spawnDimension == null
                ? de.skyengine.game.world.dimension.WorldgenRegistries.OVERWORLD.toString()
                : level.spawnDimension);
        if (de.skyengine.game.world.dimension.WorldgenRegistries.DIMENSIONS.get(dimension) == null) {
            dimension = de.skyengine.game.world.dimension.WorldgenRegistries.OVERWORLD;
        }
        return new SpawnPoint(dimension, level.spawnX, level.spawnY, level.spawnZ,
                level.spawnYaw == null ? 0F : level.spawnYaw,
                level.spawnPitch == null ? 0F : level.spawnPitch);
    }

    public void setSpawnPoint(Identifier dimension, int x, int y, int z, float yaw, float pitch) {
        var level = this.save.level();
        level.spawnDimension = dimension.toString();
        level.spawnX = x;
        level.spawnY = y;
        level.spawnZ = z;
        level.spawnYaw = yaw;
        level.spawnPitch = pitch;
        WorldSaves.saveInDirectory(this.save, this.root);
    }

    public <T> CompletableFuture<T> submitBackground(java.util.function.Supplier<T> task) {
        return this.workers.submitBackground(task);
    }

    public DimensionManager.DimensionTicket acquireDimension(Identifier id,
                                                              DimensionManager.TicketType type,
                                                              Object owner) {
        return this.dimensions.acquire(id, type, owner);
    }

    public void tickLifecycle() {
        if (this.dimensions.tickLifecycle() > 0) {
            WorldSaves.saveInDirectory(this.save, this.root);
        }
    }

    public int saveModifiedChunks(boolean materializeFalling) {
        this.players.saveAll();
        int chunks = this.dimensions.saveModifiedChunks(materializeFalling);
        WorldSaves.saveInDirectory(this.save, this.root);
        return chunks;
    }

    public boolean hasPendingSaves() {
        return this.dimensions.hasPendingSaves();
    }

    @Override
    public void dispose() {
        try {
            this.players.saveAll();
            this.dimensions.dispose();
            WorldSaves.saveInDirectory(this.save, this.root);
        } finally {
            if (this.ownsWorkers) this.workers.dispose();
        }
    }
}
