package de.skyengine.game.world;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.io.IDisposable;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.game.world.dimension.PortalLinks;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.game.world.structure.StructureAuthoringService;
import de.skyengine.game.world.structure.StructureTemplateManager;

import java.io.File;

/** Laufzeit eines geoeffneten Savegames; besitzt Dimensionen und weltweite Dienste. */
public final class World implements IDisposable {

    private final WorldSaves.WorldSave save;
    private final File root;
    private final WorldWorkerPool workers;
    private final PortalLinks portalLinks;
    private final PlayerManager players;
    private final DimensionManager dimensions;
    private final StructureTemplateManager structures;
    private final StructureAuthoringService structureAuthoring;

    public World(WorldSaves.WorldSave save, SoundManager soundManager) {
        this(save, WorldSaves.dir(save.dirName()), soundManager);
    }

    public World(WorldSaves.WorldSave save, File root, SoundManager soundManager) {
        this.save = save;
        this.root = root;
        this.workers = new WorldWorkerPool();
        this.portalLinks = new PortalLinks(this.root);
        this.players = new PlayerManager(save, this.root,
                () -> WorldSaves.saveInDirectory(save, this.root));
        this.structures = new StructureTemplateManager();
        this.structureAuthoring = new StructureAuthoringService(this.structures);
        this.dimensions = new DimensionManager(save.dirName(), save.level(), this.root,
                this.workers, this.portalLinks, soundManager, structureSnapshot());
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

    public StructureAuthoringService structureAuthoring() { return this.structureAuthoring; }

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
            this.workers.dispose();
        }
    }
}
