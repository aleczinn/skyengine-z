package de.skyengine.game;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.io.IDisposable;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.DimensionManager;
import de.skyengine.game.world.World;
import de.skyengine.game.world.PlayerLocation;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.dimension.PortalController;
import de.skyengine.game.world.dimension.PortalIndex;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.world.DimensionView;

/** Alle Objekte, die nur zwischen Weltbeitritt und Rueckkehr ins Hauptmenue existieren. */
public final class GameplaySession implements IDisposable {

    private final World world;
    private final EntityPlayer localPlayer;
    private final BlockTextureAtlas atlas;
    private final BlockEntityRenderDispatcher blockEntityRenderers;
    private DimensionManager.DimensionTicket playerDimensionTicket;
    private Dimension dimension;
    private DimensionView view;
    final PortalController portalController = new PortalController();
    PendingDimensionSwitch pendingDimensionSwitch;
    PendingArrival pendingArrival;

    static final class PendingDimensionSwitch {
        final Identifier target;
        final int x, y, z;
        final Identifier portalType;
        final boolean createReturnPortal;
        final Direction.Axis portalAxis;
        final Identifier sourceDimension;
        final String sourcePortalId, targetPortalId;
        final PlayerLocation exactDestination;

        PendingDimensionSwitch(Identifier target, int x, int y, int z, Identifier portalType,
                               boolean createReturnPortal, Direction.Axis portalAxis,
                               Identifier sourceDimension, String sourcePortalId,
                               String targetPortalId) {
            this(target, x, y, z, portalType, createReturnPortal, portalAxis,
                    sourceDimension, sourcePortalId, targetPortalId, null);
        }

        PendingDimensionSwitch(Identifier target, int x, int y, int z, Identifier portalType,
                               boolean createReturnPortal, Direction.Axis portalAxis,
                               Identifier sourceDimension, String sourcePortalId,
                               String targetPortalId, PlayerLocation exactDestination) {
            this.target = target;
            this.x = x;
            this.y = y;
            this.z = z;
            this.portalType = portalType;
            this.createReturnPortal = createReturnPortal;
            this.portalAxis = portalAxis;
            this.sourceDimension = sourceDimension;
            this.sourcePortalId = sourcePortalId;
            this.targetPortalId = targetPortalId;
            this.exactDestination = exactDestination;
        }
    }

    static final class PendingArrival {
        final int x, y, z;
        final Identifier portalType;
        final boolean createReturnPortal;
        final Direction.Axis portalAxis;
        final Identifier sourceDimension;
        final String sourcePortalId;
        final PortalIndex.Entry indexedPortal;
        final PlayerLocation exactDestination;

        PendingArrival(int x, int y, int z, Identifier portalType, boolean createReturnPortal,
                       Direction.Axis portalAxis, Identifier sourceDimension,
                       String sourcePortalId, PortalIndex.Entry indexedPortal) {
            this(x, y, z, portalType, createReturnPortal, portalAxis, sourceDimension,
                    sourcePortalId, indexedPortal, null);
        }

        PendingArrival(int x, int y, int z, Identifier portalType, boolean createReturnPortal,
                       Direction.Axis portalAxis, Identifier sourceDimension,
                       String sourcePortalId, PortalIndex.Entry indexedPortal,
                       PlayerLocation exactDestination) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.portalType = portalType;
            this.createReturnPortal = createReturnPortal;
            this.portalAxis = portalAxis;
            this.sourceDimension = sourceDimension;
            this.sourcePortalId = sourcePortalId;
            this.indexedPortal = indexedPortal;
            this.exactDestination = exactDestination;
        }
    }

    public GameplaySession(WorldSaves.WorldSave save, BlockTextureAtlas atlas,
                           BlockEntityRenderDispatcher blockEntityRenderers,
                           SoundManager soundManager) {
        this(new World(save, soundManager), atlas, blockEntityRenderers);
    }

    GameplaySession(World world) {
        this(world, null, null);
    }

    private GameplaySession(World world, BlockTextureAtlas atlas,
                            BlockEntityRenderDispatcher blockEntityRenderers) {
        this.world = world;
        this.atlas = atlas;
        this.blockEntityRenderers = blockEntityRenderers;
        this.localPlayer = this.world.players().localPlayer();
        this.playerDimensionTicket = this.world.acquireDimension(this.localPlayer.getDimensionId(),
                DimensionManager.TicketType.PLAYER, this.localPlayer.getUuid());
        this.dimension = this.playerDimensionTicket.dimension();
        this.view = this.createView(this.dimension);
        if (!this.world.players().localPlayerHasPosition()) {
            World.SpawnPoint spawn = this.world.spawnPoint();
            if (spawn == null) {
                int spawnY = this.dimension.getGenerator().sampleHeight(0, 0) + 2;
                this.localPlayer.setPosition(0.5, spawnY, 0.5);
            } else {
                this.localPlayer.setPosition(spawn.x() + 0.5, spawn.y(), spawn.z() + 0.5);
                this.localPlayer.yaw = spawn.yaw();
                this.localPlayer.pitch = spawn.pitch();
            }
        }
    }

    public World world() {
        return this.world;
    }

    public WorldSaves.WorldSave save() {
        return this.world.saveDescriptor();
    }

    public EntityPlayer player() {
        return this.localPlayer;
    }

    public Dimension dimension() {
        return this.dimension;
    }

    public DimensionView view() {
        return this.view;
    }

    /** Erwirbt das Ziel vor Freigabe der Quelle und aktualisiert den Spieler atomar. */
    public Dimension switchDimension(Identifier target, Object transferOwner) {
        if (target.equals(this.dimension.getDimensionId())) return this.dimension;
        DimensionManager.DimensionTicket transfer = this.world.acquireDimension(target,
                DimensionManager.TicketType.PORTAL_TRANSFER, transferOwner);
        DimensionManager.DimensionTicket replacement = null;
        try {
            replacement = this.world.acquireDimension(target,
                    DimensionManager.TicketType.PLAYER, this.localPlayer.getUuid());
            Dimension next = replacement.dimension();
            DimensionView nextView = this.createView(next);
            DimensionView previousView = this.view;
            if (previousView != null) previousView.dispose();
            DimensionManager.DimensionTicket previous = this.playerDimensionTicket;
            this.playerDimensionTicket = replacement;
            this.dimension = next;
            this.view = nextView;
            this.localPlayer.setDimensionId(target);
            if (previous != null) previous.close();
            return next;
        } catch (RuntimeException e) {
            if (replacement != null) replacement.close();
            throw e;
        } finally {
            transfer.close();
        }
    }

    @Override
    public void dispose() {
        this.pendingDimensionSwitch = null;
        this.pendingArrival = null;
        this.portalController.reset();
        if (this.view != null) this.view.dispose();
        this.view = null;
        if (this.playerDimensionTicket != null) this.playerDimensionTicket.close();
        this.playerDimensionTicket = null;
        this.world.dispose();
    }

    private DimensionView createView(Dimension dimension) {
        return this.atlas == null ? null
                : new DimensionView(dimension, this.atlas, this.blockEntityRenderers);
    }
}
