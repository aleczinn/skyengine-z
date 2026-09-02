package de.skyengine.server.world;

import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.player.PlayerMovementSimulation;
import de.skyengine.shared.gameplay.BlockActionRequest;
import de.skyengine.shared.gameplay.InventoryActionRequest;
import de.skyengine.shared.gameplay.EntityActionRequest;
import de.skyengine.shared.network.pack.PackDescriptor;
import de.skyengine.shared.network.pack.RegistryMapping;

import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Tick-owned world boundary. The existing world implementation is migrated behind this interface. */
public interface ServerWorldRuntime extends AutoCloseable {
    Path directory();
    void tick(long serverTick);
    void autosave(long serverTick);
    /** Required pack manifest advertised before registry synchronization. */
    default List<PackDescriptor> packManifest() { return List.of(); }
    /** Ordered runtime IDs. Implementations must keep each identifier list in network-ID order. */
    default List<RegistryMapping> registryMappings() {
        return List.of(new RegistryMapping("block_state", List.of()));
    }
    /** Drains only dirty entity changes produced by the preceding world tick. */
    default List<EntityReplicationUpdate> drainEntityUpdates(long serverTick) { return List.of(); }
    /** Drains authoritative simulation changes (fluids, redstone, pistons, block entities, etc.). */
    default List<ChunkBlockChanges> drainBlockChanges() { return List.of(); }
    default List<BlockEntityReplicationUpdate> drainBlockEntityUpdates() { return List.of(); }
    default List<WorldSoundEvent> drainSoundEvents() { return List.of(); }
    /**
     * Produces an immutable snapshot without exposing tick-owned world objects to compression/network workers.
     * Implementations may load or deterministically generate previously unseen terrain.
     */
    default CompletionStage<Optional<ChunkColumnSnapshot>> requestChunkSnapshot(
            String dimension, int chunkX, int chunkZ) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
    default PlayerStateSnapshot playerJoined(PlayerIdentity identity, int entityId, long serverTick) {
        return new PlayerStateSnapshot(serverTick, 0, "skyengine:overworld",
                0.5, 80, 0.5, 0, 0, 0, 0, 0, false, PlayerGameMode.CREATIVE, 0);
    }
    default PlayerStateSnapshot applyPlayerInput(PlayerIdentity identity, int entityId,
                                                 PlayerStateSnapshot previous, PlayerInputFrame input,
                                                 long serverTick) {
        // The legacy EntityPlayer/Dimension adapter replaces this conservative headless fallback.
        return new PlayerStateSnapshot(serverTick, input.sequence(), previous.dimension(),
                previous.x(), previous.y(), previous.z(), 0, 0, 0,
                input.yaw(), input.pitch(), previous.grounded(), previous.gameMode(), previous.movementState());
    }
    default PlayerStateSnapshot changePlayerGameMode(PlayerIdentity identity, int entityId,
                                                     PlayerStateSnapshot previous, PlayerGameMode mode,
                                                     long serverTick) {
        return PlayerMovementSimulation.withGameMode(previous, mode, serverTick);
    }
    /** Authoritative death-screen respawn. Returns the unchanged state when respawn is invalid. */
    default PlayerStateSnapshot respawnPlayer(PlayerIdentity identity, int entityId,
                                              PlayerStateSnapshot previous, long serverTick) {
        return previous;
    }
    default void playerLeft(PlayerIdentity identity, int entityId, PlayerStateSnapshot state) { }
    /** Initial authoritative player inventory (container 0) sent immediately after PLAY starts. */
    default InventoryActionOutcome playerInventory(PlayerIdentity identity) {
        return InventoryActionOutcome.rejected(0, "Inventory is unavailable");
    }

    default InventoryActionOutcome containerInventory(PlayerIdentity identity, int containerId) {
        return containerId == 0 ? playerInventory(identity)
                : InventoryActionOutcome.rejected(0, "Container is unavailable");
    }

    default void closeContainer(PlayerIdentity identity, int containerId) { }

    default ContainerOpenData openPlayerInventory(PlayerIdentity identity) { return null; }

    default int containerInventoryRevision(PlayerIdentity identity, int containerId) {
        return containerId == 0 ? playerInventoryRevision(identity) : Integer.MIN_VALUE;
    }
    default int[] containerData(PlayerIdentity identity, int containerId) { return new int[0]; }

    /** Cheap tick-thread revision probe; avoids rebuilding a full inventory every player tick. */
    default int playerInventoryRevision(PlayerIdentity identity) { return 0; }
    default BlockActionOutcome handleBlockAction(PlayerIdentity identity, PlayerStateSnapshot state,
                                                 BlockActionRequest request, long serverTick) {
        return BlockActionOutcome.rejected(request.actionId(), "Block interactions are unavailable");
    }
    default InventoryActionOutcome handleInventoryAction(PlayerIdentity identity,
                                                         InventoryActionRequest request, long serverTick) {
        return InventoryActionOutcome.rejected(request.transactionId(), "Inventory is unavailable");
    }
    default EntityActionOutcome handleEntityAction(PlayerIdentity identity,
                                                   EntityActionRequest request, long serverTick) {
        return EntityActionOutcome.rejected(request.actionId(), "Entity interactions are unavailable");
    }
    @Override void close();
}
