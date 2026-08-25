package de.skyengine.game.world;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.io.IDisposable;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.game.world.dimension.PortalLinks;
import de.skyengine.game.world.save.WorldSaves;

import java.io.File;

/** Laufzeit eines geoeffneten Savegames; besitzt Dimensionen und weltweite Dienste. */
public final class World implements IDisposable {

    private final WorldSaves.WorldSave save;
    private final File root;
    private final WorldWorkerPool workers;
    private final PortalLinks portalLinks;
    private final PlayerManager players;
    private final DimensionManager dimensions;

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
        this.dimensions = new DimensionManager(save.dirName(), save.level(), this.root,
                this.workers, this.portalLinks, soundManager);
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
