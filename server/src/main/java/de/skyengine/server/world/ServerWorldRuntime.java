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
import de.skyengine.shared.gameplay.PlayerAbilityAction;
import de.skyengine.shared.player.PlayerMovementState;
import de.skyengine.shared.network.pack.PackDescriptor;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.game.physics.ChunkMovementLimiter;

import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

/** Tick-owned world boundary. The existing world implementation is migrated behind this interface. */
public interface ServerWorldRuntime extends AutoCloseable {
    record WorkerLaneStats(int lane, int queued, int running, long completed,
                           long oldestQueuedAgeNanos, long queueWaitMedianNanos,
                           long queueWaitP95Nanos, double completedPerSecond) { }
    record WorkerStats(int workers, int active, int queued, List<WorkerLaneStats> lanes) {
        public WorkerStats { lanes = List.copyOf(lanes); }
        public WorkerStats(int workers, int active, int queued) { this(workers, active, queued, List.of()); }
    }
    Path directory();
    void tick(long serverTick);
    void autosave(long serverTick);
    default WorkerStats workerStats() { return new WorkerStats(0, 0, 0); }
    /** Last tick-owned count of resident authoritative chunks in player-relevant dimensions. */
    default int residentChunkCount() { return 0; }
    default ReplicationCacheBudget replicationCacheBudget() { return null; }
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
    default ChunkSnapshotTicket requestChunkSnapshot(
            String dimension, int chunkX, int chunkZ) {
        return ChunkSnapshotTicket.completed(Optional.empty());
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
                input.yaw(), input.pitch(), previous.grounded(), previous.gameMode(), previous.movementState(),
                previous.health(), previous.foodLevel(), previous.saturation(), previous.selectedHotbarSlot(),
                previous.vehicleEntityId(), previous.spectatorFlySpeed());
    }
    default PlayerStateSnapshot applyPlayerInput(PlayerIdentity identity, int entityId,
                                                 PlayerStateSnapshot previous, PlayerInputFrame input,
                                                 long serverTick,
                                                 ChunkMovementLimiter.Availability availability) {
        return applyPlayerInput(identity, entityId, previous, input, serverTick);
    }
    /** Applies an edge-triggered ability exactly once without advancing movement physics. */
    default PlayerStateSnapshot applyPlayerAbility(PlayerIdentity identity, int entityId,
                                                   PlayerStateSnapshot previous, PlayerAbilityAction action,
                                                   long serverTick) {
        if (action == PlayerAbilityAction.CYCLE_GAME_MODE) {
            return PlayerMovementSimulation.withGameMode(previous, previous.gameMode().next(), serverTick);
        }
        int movement = previous.movementState();
        float speed = previous.spectatorFlySpeed();
        if (action == PlayerAbilityAction.TOGGLE_FLY && previous.gameMode() == PlayerGameMode.CREATIVE) {
            movement ^= PlayerMovementState.FLYING;
        } else if (previous.gameMode() == PlayerGameMode.SPECTATOR) {
            if (action == PlayerAbilityAction.SPECTATOR_SPEED_UP) speed += 0.5F;
            if (action == PlayerAbilityAction.SPECTATOR_SPEED_DOWN) speed -= 0.5F;
            speed = Math.clamp(speed, 1.0F, 10.0F);
        }
        return new PlayerStateSnapshot(serverTick, previous.lastProcessedInputSequence(), previous.dimension(),
                previous.x(), previous.y(), previous.z(), previous.velocityX(), previous.velocityY(),
                previous.velocityZ(), previous.yaw(), previous.pitch(), previous.grounded(), previous.gameMode(),
                movement, previous.health(), previous.foodLevel(), previous.saturation(),
                previous.selectedHotbarSlot(), previous.vehicleEntityId(), speed);
    }
    default PlayerStateSnapshot changePlayerGameMode(PlayerIdentity identity, int entityId,
                                                     PlayerStateSnapshot previous, PlayerGameMode mode,
                                                     long serverTick) {
        return PlayerMovementSimulation.withGameMode(previous, mode, serverTick);
    }
    default PlayerStateSnapshot selectHotbarSlot(PlayerIdentity identity, int entityId,
                                                 PlayerStateSnapshot previous, int slot,
                                                 long serverTick) {
        return new PlayerStateSnapshot(serverTick, previous.lastProcessedInputSequence(), previous.dimension(),
                previous.x(), previous.y(), previous.z(), previous.velocityX(), previous.velocityY(),
                previous.velocityZ(), previous.yaw(), previous.pitch(), previous.grounded(), previous.gameMode(),
                previous.movementState(), previous.health(), previous.foodLevel(), previous.saturation(), slot,
                previous.vehicleEntityId(), previous.spectatorFlySpeed());
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
    /** Records a harmless arm-swing presentation event for interested observers. */
    default void playerSwing(PlayerIdentity identity, int entityId) { }
    @Override void close();
}
