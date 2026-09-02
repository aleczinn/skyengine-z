package de.skyengine.server.world;

import de.skyengine.core.resource.Resources;
import de.skyengine.game.Gamemode;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.entity.PrimedTntEntity;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.entity.PlayerControls;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.PlayerBlockActions;
import de.skyengine.game.world.DimensionManager;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkSnapshotEncoder;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.dimension.PortalController;
import de.skyengine.game.world.dimension.PortalCoordinates;
import de.skyengine.game.world.dimension.PortalDefinition;
import de.skyengine.game.world.dimension.PortalIndex;
import de.skyengine.game.world.dimension.PortalLinks;
import de.skyengine.game.world.dimension.NetherPortalShape;
import de.skyengine.game.world.effect.WorldSoundSink;
import de.skyengine.audio.BlockSoundGroup;
import de.skyengine.audio.BlockOpenSound;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.DataTagIO;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.FoodItem;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.DispenserBlockEntity;
import de.skyengine.game.world.block.entity.FurnaceBlockEntity;
import de.skyengine.game.world.block.entity.HopperBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.entity.CompoundItemStorage;
import de.skyengine.game.world.recipe.CraftingMenu;
import de.skyengine.game.world.recipe.RecipeManager;
import de.skyengine.server.ServerConfig;
import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.shared.gameplay.BlockActionRequest;
import de.skyengine.shared.gameplay.AuthoritativeBlockCorrection;
import de.skyengine.shared.gameplay.InventoryActionRequest;
import de.skyengine.shared.gameplay.EntityActionRequest;
import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.gameplay.ContainerKind;
import de.skyengine.shared.gameplay.WorldSoundType;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.gameplay.PlayerAbilityAction;
import de.skyengine.shared.player.PlayerMovementState;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.entity.NetworkEntitySnapshot;
import de.skyengine.shared.entity.NetworkEntityTypes;
import de.skyengine.shared.entity.EntityEventTypes;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The actual server-side game world. This deliberately reuses the same World, Dimension,
 * generator and EntityPlayer implementation as singleplayer; networking is only an adapter
 * around that authoritative state.
 */
public final class AuthoritativeWorldRuntime implements ServerWorldRuntime {
    private static final int NETWORK_LOAD_HALO = 2;
    private record ColumnKey(String dimension, int x, int z) { }

    private final ServerConfig config;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final WorldSaves.WorldSave save;
    private final World world;
    private final Map<UUID, EntityPlayer> players = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerNetworkIds = new HashMap<>();
    private final IdentityHashMap<Entity, Integer> entityNetworkIds = new IdentityHashMap<>();
    private final Map<Integer, Entity> worldEntitiesByNetworkId = new HashMap<>();
    private final Map<Integer, Identifier> worldEntityDimensions = new HashMap<>();
    /** Kept disjoint from connection-owned player IDs without coupling the world to sessions. */
    private int nextWorldEntityId = 1_000_000;
    private record MiningState(int x, int y, int z, int expectedStateId, float progress) { }
    private final Map<UUID, MiningState> mining = new HashMap<>();
    private final Map<UUID, Integer> inventoryRevisions = new HashMap<>();
    private final Map<UUID, ItemStack> carriedStacks = new HashMap<>();
    private final Map<UUID, Integer> eatingTicks = new HashMap<>();
    private final Map<UUID, PortalController> portalControllers = new HashMap<>();
    private record PendingSimplePortalArrival(int x, int y, int z, Identifier portalType,
                                              boolean createReturnPortal) { }
    private final Map<UUID, PendingSimplePortalArrival> pendingSimplePortalArrivals = new HashMap<>();
    private record PendingNetherPortalArrival(int x, int y, int z, Identifier portalType,
                                              Direction.Axis portalAxis, Identifier sourceDimension,
                                              String sourcePortalId, PortalIndex.Entry indexedPortal) { }
    private final Map<UUID, PendingNetherPortalArrival> pendingNetherPortalArrivals = new HashMap<>();
    private static final class OpenContainer {
        final int id;
        final ContainerKind kind;
        final Identifier dimension;
        final int x, y, z;
        final ItemStorage blockInventory;
        final ItemStorage combined;
        final CraftingMenu crafting;
        int revision;
        int contentHash;

        OpenContainer(int id, ContainerKind kind, Identifier dimension, int x, int y, int z,
                      ItemStorage blockInventory, ItemStorage playerInventory) {
            this(id, kind, dimension, x, y, z, blockInventory,
                    new CompoundItemStorage(blockInventory, playerInventory), null);
        }

        OpenContainer(int id, ContainerKind kind, Identifier dimension, int x, int y, int z,
                      ItemStorage blockInventory, ItemStorage combined, CraftingMenu crafting) {
            this.id = id; this.kind = kind; this.dimension = dimension;
            this.x = x; this.y = y; this.z = z; this.blockInventory = blockInventory;
            this.combined = combined;
            this.crafting = crafting;
            this.contentHash = storageHash(this.combined);
        }
    }
    private final Map<UUID, OpenContainer> openContainers = new HashMap<>();
    private int nextContainerId = 1;
    private final Map<UUID, DimensionManager.DimensionTicket> playerTickets = new HashMap<>();
    private static final class SnapshotWork {
        final List<ChunkSnapshotTicket> tickets = new ArrayList<>();
        boolean encoding;
    }
    private record SnapshotCompletion(ColumnKey key, SnapshotWork work,
                                      ChunkColumnSnapshot snapshot, Throwable failure) { }
    private final Map<ColumnKey, SnapshotWork> snapshots = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<SnapshotCompletion> completedSnapshots = new ConcurrentLinkedQueue<>();
    private ExecutorService snapshotWorkers;
    private int snapshotWorkerCount;
    private int activeSnapshotJobs;
    private final List<ChunkBlockChanges> pendingBlockChanges = new ArrayList<>();
    private final List<BlockEntityReplicationUpdate> pendingBlockEntityUpdates = new ArrayList<>();
    private final List<WorldSoundEvent> pendingSoundEvents = new ArrayList<>();
    private final List<EntityReplicationUpdate.Event> pendingEntityEvents = new ArrayList<>();
    private final List<EntityReplicationUpdate.Despawn> pendingEntityDespawns = new ArrayList<>();
    private final IdentityHashMap<Dimension, Boolean> networkConfiguredDimensions = new IdentityHashMap<>();
    private final List<RegistryMapping> registryMappings;
    private final Map<Item, Integer> itemNetworkIds = new IdentityHashMap<>();
    private final List<Item> networkItems = new ArrayList<>();
    private boolean closed;

    public AuthoritativeWorldRuntime(ServerConfig config) throws IOException {
        this(config, config.worldDirectory());
    }

    /** Oeffnet einen expliziten Saveordner fuer den Integrated Server. */
    public AuthoritativeWorldRuntime(ServerConfig config, Path worldDirectory) throws IOException {
        this.config = config;
        this.directory = worldDirectory.toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(this.directory);
        this.lockChannel = FileChannel.open(this.directory.resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        this.lock = this.lockChannel.tryLock();
        if (this.lock == null) {
            this.lockChannel.close();
            throw new IOException("World is already open: " + this.directory);
        }
        try {
            bootstrapGameplay();
            int seed = new SecureRandom().nextInt();
            this.save = WorldSaves.openOrCreate(this.directory.toFile(), config.world(), seed);
            this.world = new World(this.save, this.directory.toFile(), WorldSoundSink.NONE, false,
                    config.workerThreads());
            this.snapshotWorkerCount = Math.min(4, Math.max(1, config.workerThreads() / 4));
            this.snapshotWorkers = Executors.newFixedThreadPool(this.snapshotWorkerCount,
                    Thread.ofPlatform().daemon().name("Chunk Snapshot-", 0).factory());
            this.registryMappings = createRegistryMappings();
            this.networkItems.add(null);
            int itemId = 1;
            for (Item item : Registries.ITEM.values()) {
                this.itemNetworkIds.put(item, itemId++);
                this.networkItems.add(item);
            }
        } catch (IOException | RuntimeException failure) {
            if (this.snapshotWorkers != null) this.snapshotWorkers.shutdownNow();
            try { this.lock.release(); } catch (IOException ignored) { }
            try { this.lockChannel.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    private static synchronized void bootstrapGameplay() {
        if (BlockRegistry.isBaked()) return;
        Resources.initialize();
        Blocks.bootstrap(Resources.defaultGameRoot().resolve("blocks").toFile());
        WorldgenRegistries.bootstrap();
    }

    private static List<RegistryMapping> createRegistryMappings() {
        List<String> states = new ArrayList<>(BlockRegistry.getStateCount());
        for (int id = 0; id < BlockRegistry.getStateCount(); id++) {
            states.add(BlockStateCodec.encode(BlockRegistry.getState(id)));
        }
        List<String> biomes = new ArrayList<>(Biomes.ALL.length);
        for (Biome biome : Biomes.ALL) biomes.add(Identifier.of(biome.name).toString());
        List<String> items = new ArrayList<>(Registries.ITEM.size() + 1);
        // Network item id 0 is reserved for the empty stack.
        items.add(Identifier.of("empty").toString());
        Registries.ITEM.values().forEach(item -> items.add(item.getId().toString()));
        return List.of(new RegistryMapping("block_state", states),
                new RegistryMapping("biome", biomes), new RegistryMapping("item", items));
    }

    @Override public Path directory() { return this.directory; }
    public int seed() { return this.save.level().seed; }
    @Override public List<RegistryMapping> registryMappings() { return this.registryMappings; }

    @Override
    public void tick(long serverTick) {
        Map<Identifier, List<EntityPlayer>> byDimension = new LinkedHashMap<>();
        for (EntityPlayer player : this.players.values()) {
            byDimension.computeIfAbsent(player.getDimensionId(), ignored -> new ArrayList<>()).add(player);
        }
        for (Map.Entry<Identifier, List<EntityPlayer>> entry : byDimension.entrySet()) {
            Dimension dimension = this.world.dimensions().getLoaded(entry.getKey());
            if (dimension != null) {
                configureNetworkDimension(dimension);
                dimension.updatePlayers(entry.getValue());
                for (EntityPlayer player : entry.getValue()) {
                    this.finalizeSimplePortalArrival(player, dimension);
                    this.finalizeNetherPortalArrival(player, dimension);
                    this.pickupItems(player, dimension);
                }
                this.pendingBlockChanges.addAll(chunkChanges(dimension,
                        dimension.drainNetworkBlockMutations()));
                this.collectBlockEntityUpdates(dimension);
            }
        }
        completeReadySnapshots();
        this.world.tickLifecycle();
        for (OpenContainer open : this.openContainers.values()) {
            int hash = storageHash(open.combined);
            if (hash != open.contentHash) {
                open.contentHash = hash;
                open.revision++;
            }
        }
    }

    @Override public void autosave(long serverTick) { this.world.saveModifiedChunks(false); }

    @Override
    public ChunkSnapshotTicket requestChunkSnapshot(
            String dimensionName, int chunkX, int chunkZ) {
        if (this.closed) return ChunkSnapshotTicket.completed(Optional.empty());
        Identifier id;
        try { id = Identifier.of(dimensionName); }
        catch (IllegalArgumentException invalid) {
            return ChunkSnapshotTicket.completed(Optional.empty());
        }
        Dimension dimension = this.world.dimensions().getLoaded(id);
        if (dimension == null) return ChunkSnapshotTicket.completed(Optional.empty());
        ChunkSnapshotTicket ticket = new ChunkSnapshotTicket();
        this.snapshots.computeIfAbsent(new ColumnKey(id.toString(), chunkX, chunkZ),
                ignored -> new SnapshotWork()).tickets.add(ticket);
        return ticket;
    }

    private void completeReadySnapshots() {
        SnapshotCompletion completion;
        while ((completion = this.completedSnapshots.poll()) != null) {
            this.activeSnapshotJobs = Math.max(0, this.activeSnapshotJobs - 1);
            // A cancelled job can finish after a newer request for the same column was queued.
            // Remove only the exact work item that produced this completion.
            this.snapshots.remove(completion.key(), completion.work());
            for (ChunkSnapshotTicket ticket : completion.work().tickets) {
                if (completion.failure() == null) ticket.complete(Optional.of(completion.snapshot()));
                else ticket.completeExceptionally(completion.failure());
            }
        }

        var iterator = this.snapshots.entrySet().iterator();
        while (iterator.hasNext() && this.activeSnapshotJobs < this.snapshotWorkerCount) {
            Map.Entry<ColumnKey, SnapshotWork> entry = iterator.next();
            SnapshotWork work = entry.getValue();
            work.tickets.removeIf(ChunkSnapshotTicket::cancelled);
            if (work.tickets.isEmpty()) {
                iterator.remove();
                continue;
            }
            if (work.encoding) continue;
            ColumnKey key = entry.getKey();
            Dimension dimension = this.world.dimensions().getLoaded(Identifier.of(key.dimension()));
            if (dimension == null) continue;
            Chunk chunk = dimension.getChunkManager().getChunk(key.x(), key.z());
            if (!isSnapshotReady(chunk)) continue;
            work.encoding = true;
            this.activeSnapshotJobs++;
            this.snapshotWorkers.execute(() -> {
                try {
                    ChunkColumnSnapshot snapshot = ChunkSnapshotEncoder.encode(key.dimension(), chunk,
                            dimension.getGenerator());
                    this.completedSnapshots.add(new SnapshotCompletion(key, work, snapshot, null));
                } catch (Throwable failure) {
                    this.completedSnapshots.add(new SnapshotCompletion(key, work, null, failure));
                }
            });
        }
    }

    private static boolean isSnapshotReady(Chunk chunk) {
        return chunk != null && chunk.status.isAtLeast(ChunkStatus.LIT);
    }

    @Override
    public PlayerStateSnapshot playerJoined(PlayerIdentity identity, int entityId, long serverTick) {
        EntityPlayer player = this.world.players().loadOrCreate(identity.uuid());
        boolean persisted = PlayerIO.playerFile(this.directory.toFile(), identity.uuid()).isFile();
        this.players.put(identity.uuid(), player);
        this.playerNetworkIds.put(identity.uuid(), entityId);
        this.inventoryRevisions.putIfAbsent(identity.uuid(), 0);
        this.carriedStacks.put(identity.uuid(), ItemStack.EMPTY);
        this.portalControllers.computeIfAbsent(identity.uuid(), ignored -> new PortalController()).reset();
        DimensionManager.DimensionTicket ticket = this.world.acquireDimension(player.getDimensionId(),
                DimensionManager.TicketType.PLAYER, identity.uuid());
        DimensionManager.DimensionTicket previous = this.playerTickets.put(identity.uuid(), ticket);
        if (previous != null) previous.close();
        Dimension dimension = ticket.dimension();
        configureNetworkDimension(dimension);
        dimension.getChunkManager().setRenderDistance(this.config.viewDistance() + NETWORK_LOAD_HALO);
        dimension.setSimulationDistance(this.config.simulationDistance());
        if (!persisted) {
            World.SpawnPoint spawn = this.world.spawnPoint();
            if (spawn != null && spawn.dimension().equals(player.getDimensionId())) {
                player.setPosition(spawn.x() + 0.5, spawn.y(), spawn.z() + 0.5);
                player.yaw = spawn.yaw();
                player.pitch = spawn.pitch();
            } else {
                int y = dimension.getGenerator().sampleHeight(0, 0) + 2;
                player.setPosition(0.5, y, 0.5);
            }
            player.snapPrevToCurrent();
        }
        return snapshot(player, serverTick, 0);
    }

    @Override
    public PlayerStateSnapshot applyPlayerInput(PlayerIdentity identity, int entityId,
                                                 PlayerStateSnapshot previous, PlayerInputFrame input,
                                                 long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null) return previous;
        player.yaw = input.yaw();
        player.pitch = input.pitch();
        if (this.pendingSimplePortalArrivals.containsKey(identity.uuid())
                || this.pendingNetherPortalArrivals.containsKey(identity.uuid())) {
            player.motionX = player.motionY = player.motionZ = 0;
            player.snapPrevToCurrent();
            return snapshot(player, serverTick, input.sequence());
        }
        Dimension dimension = this.world.dimensions().getLoaded(player.getDimensionId());
        if (dimension != null && collisionAreaReady(dimension, player)) {
            player.update(new PlayerControls(input.forward(), input.strafe(),
                    input.pressed(PlayerInputFrame.JUMP), input.pressed(PlayerInputFrame.SNEAK),
                    input.pressed(PlayerInputFrame.SPRINT),
                    input.pressed(PlayerInputFrame.SNEAK_TOGGLE_MODE),
                    input.pressed(PlayerInputFrame.SPRINT_TOGGLE_MODE)), dimension);
            float fallDamage = player.consumeFallDamage();
            if (player.consumeHurt()) {
                this.pendingEntityEvents.add(new EntityReplicationUpdate.Event(entityId,
                        EntityEventTypes.HURT, Math.max(0, Math.round(fallDamage * 1000F))));
            }
            updateEating(identity.uuid(), player, input.pressed(PlayerInputFrame.USE));
            MiningState miningState = this.mining.get(identity.uuid());
            if (miningState != null) {
                if (!input.pressed(PlayerInputFrame.ATTACK)
                        || dimension.getBlock(miningState.x(), miningState.y(), miningState.z())
                        != miningState.expectedStateId()) {
                    this.mining.remove(identity.uuid());
                } else {
                    float progress = miningState.progress() + PlayerBlockActions.destroyProgress(player,
                            Blocks.getState(miningState.expectedStateId()));
                    this.mining.put(identity.uuid(), new MiningState(miningState.x(), miningState.y(),
                            miningState.z(), miningState.expectedStateId(), progress));
                }
            }
            if (!player.isDead()) {
                PortalController.Travel travel = this.portalControllers
                        .computeIfAbsent(identity.uuid(), ignored -> new PortalController())
                        .tick(dimension, player);
                if (travel != null) transferPlayer(identity, player, dimension, travel);
            }
        } else {
            player.motionX = player.motionY = player.motionZ = 0;
            player.snapPrevToCurrent();
        }
        return snapshot(player, serverTick, input.sequence());
    }

    @Override
    public PlayerStateSnapshot applyPlayerAbility(PlayerIdentity identity, int entityId,
                                                   PlayerStateSnapshot previous, PlayerAbilityAction action,
                                                   long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null) return previous;
        switch (action) {
            case CYCLE_GAME_MODE -> player.setGamemode(player.getGamemode().next());
            case TOGGLE_FLY -> player.toggleFlying();
            case SPECTATOR_SPEED_UP -> player.adjustSpectatorFlySpeed(1);
            case SPECTATOR_SPEED_DOWN -> player.adjustSpectatorFlySpeed(-1);
        }
        return snapshot(player, serverTick, previous.lastProcessedInputSequence());
    }

    @Override
    public PlayerStateSnapshot selectHotbarSlot(PlayerIdentity identity, int entityId,
                                                PlayerStateSnapshot previous, int slot,
                                                long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null) return previous;
        player.setSelectedSlot(slot);
        return snapshot(player, serverTick, previous.lastProcessedInputSequence());
    }

    private static boolean collisionAreaReady(Dimension dimension, EntityPlayer player) {
        int centerX = (int) Math.floor(player.x) >> ChunkSection.SHIFT;
        int centerZ = (int) Math.floor(player.z) >> ChunkSection.SHIFT;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk chunk = dimension.getChunkManager().getChunk(centerX + dx, centerZ + dz);
                if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return false;
            }
        }
        return true;
    }

    @Override
    public PlayerStateSnapshot changePlayerGameMode(PlayerIdentity identity, int entityId,
                                                     PlayerStateSnapshot previous, PlayerGameMode mode,
                                                     long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null) return previous;
        player.setGamemode(fromNetwork(mode));
        return snapshot(player, serverTick, previous.lastProcessedInputSequence());
    }

    @Override
    public PlayerStateSnapshot respawnPlayer(PlayerIdentity identity, int entityId,
                                             PlayerStateSnapshot previous, long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null || !player.isDead()) return previous;
        World.SpawnPoint spawn = this.world.spawnPoint();
        Identifier targetDimension = spawn == null
                ? WorldgenRegistries.OVERWORLD : spawn.dimension();
        DimensionManager.DimensionTicket nextTicket = this.world.acquireDimension(targetDimension,
                DimensionManager.TicketType.PLAYER, identity.uuid());
        Dimension dimension = nextTicket.dimension();
        dimension.getChunkManager().setRenderDistance(this.config.viewDistance() + NETWORK_LOAD_HALO);
        dimension.setSimulationDistance(this.config.simulationDistance());
        DimensionManager.DimensionTicket oldTicket = this.playerTickets.put(identity.uuid(), nextTicket);
        if (oldTicket != null) oldTicket.close();

        player.setDimensionId(targetDimension);
        if (spawn == null) {
            int y = dimension.getGenerator().sampleHeight(0, 0) + 2;
            player.setPosition(0.5, y, 0.5);
            player.yaw = player.pitch = 0;
        } else {
            player.setPosition(spawn.x() + 0.5, spawn.y(), spawn.z() + 0.5);
            player.yaw = spawn.yaw();
            player.pitch = spawn.pitch();
        }
        player.motionX = player.motionY = player.motionZ = 0;
        player.resetVitals();
        player.snapPrevToCurrent();
        this.mining.remove(identity.uuid());
        this.eatingTicks.remove(identity.uuid());
        this.portalControllers.remove(identity.uuid());
        this.pendingSimplePortalArrivals.remove(identity.uuid());
        this.pendingNetherPortalArrivals.remove(identity.uuid());
        return snapshot(player, serverTick, previous.lastProcessedInputSequence());
    }

    @Override
    public void playerLeft(PlayerIdentity identity, int entityId, PlayerStateSnapshot state) {
        OpenContainer open = this.openContainers.get(identity.uuid());
        if (open != null) this.closeContainer(identity, open.id);
        EntityPlayer player = this.players.remove(identity.uuid());
        this.playerNetworkIds.remove(identity.uuid());
        this.mining.remove(identity.uuid());
        this.inventoryRevisions.remove(identity.uuid());
        this.carriedStacks.remove(identity.uuid());
        this.eatingTicks.remove(identity.uuid());
        this.portalControllers.remove(identity.uuid());
        this.pendingSimplePortalArrivals.remove(identity.uuid());
        this.pendingNetherPortalArrivals.remove(identity.uuid());
        if (player != null) this.world.players().remove(identity.uuid(), true);
        DimensionManager.DimensionTicket ticket = this.playerTickets.remove(identity.uuid());
        if (ticket != null) ticket.close();
        // Player entities are not part of entityNetworkIds. Publish their lifecycle explicitly so
        // every interested client removes the replica before this network ID can ever be reused.
        if (entityId > 0) this.pendingEntityDespawns.add(new EntityReplicationUpdate.Despawn(entityId, 0));
    }

    @Override
    public InventoryActionOutcome playerInventory(PlayerIdentity identity) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null) return InventoryActionOutcome.rejected(0, "Player is not loaded");
        List<NetworkItemStack> content = new ArrayList<>(player.getInventory().size());
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            content.add(toNetworkStack(player.getInventory().get(slot)));
        }
        return new InventoryActionOutcome(0, true, "", 0,
                this.inventoryRevisions.getOrDefault(identity.uuid(), 0), content,
                toNetworkStack(this.carriedStacks.getOrDefault(identity.uuid(), ItemStack.EMPTY)));
    }

    @Override
    public ContainerOpenData openPlayerInventory(PlayerIdentity identity) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null) return null;
        OpenContainer previous = this.openContainers.get(identity.uuid());
        if (previous != null) closeContainer(identity, previous.id);
        Dimension dimension = this.world.dimensions().getLoaded(player.getDimensionId());
        if (dimension == null) return null;
        CraftingMenu crafting = new CraftingMenu(2, 2, RecipeManager.CRAFTING, player.getInventory(),
                stack -> dimension.throwItem(player, stack));
        ItemStorage craftingSlots = new CompoundItemStorage(crafting.input(), crafting.output());
        ItemStorage combined = new CompoundItemStorage(craftingSlots, player.getInventory());
        int id = this.nextContainerId++;
        if (this.nextContainerId <= 0) this.nextContainerId = 1;
        OpenContainer open = new OpenContainer(id, ContainerKind.PLAYER_INVENTORY,
                player.getDimensionId(), 0, 0, 0, craftingSlots, combined, crafting);
        this.openContainers.put(identity.uuid(), open);
        return new ContainerOpenData(id, ContainerKind.PLAYER_INVENTORY, craftingSlots.size(), 2,
                player.getDimensionId().toString(), 0, 0, 0);
    }

    @Override
    public InventoryActionOutcome containerInventory(PlayerIdentity identity, int containerId) {
        if (containerId == 0) return playerInventory(identity);
        OpenContainer open = this.openContainers.get(identity.uuid());
        if (open == null || open.id != containerId) {
            return InventoryActionOutcome.rejected(0, "Container is not open");
        }
        List<NetworkItemStack> content = new ArrayList<>(open.combined.size());
        for (int slot = 0; slot < open.combined.size(); slot++) {
            content.add(toNetworkStack(open.combined.get(slot)));
        }
        return new InventoryActionOutcome(0, true, "", open.id, open.revision, content,
                toNetworkStack(this.carriedStacks.getOrDefault(identity.uuid(), ItemStack.EMPTY)));
    }

    @Override
    public void closeContainer(PlayerIdentity identity, int containerId) {
        OpenContainer open = this.openContainers.get(identity.uuid());
        if (open == null || (containerId > 0 && open.id != containerId)) return;
        this.openContainers.remove(identity.uuid());
        EntityPlayer player = this.players.get(identity.uuid());
        if (open.crafting != null) open.crafting.close();
        ItemStack carried = this.carriedStacks.getOrDefault(identity.uuid(), ItemStack.EMPTY);
        if (player != null && !carried.isEmpty()) {
            ItemStack remaining = player.getInventory().insert(carried);
            this.carriedStacks.put(identity.uuid(), ItemStack.EMPTY);
            if (!remaining.isEmpty()) {
                Dimension playerDimension = this.world.dimensions().getLoaded(player.getDimensionId());
                if (playerDimension != null) playerDimension.throwItem(player, remaining);
            }
            bumpInventoryRevision(identity.uuid());
        }
        Dimension dimension = this.world.dimensions().getLoaded(open.dimension);
        BlockEntity entity = dimension == null ? null : dimension.getBlockEntity(open.x, open.y, open.z);
        if (open.kind == ContainerKind.CHEST && entity instanceof ChestBlockEntity chest
                && !isChestViewed(open.dimension, open.x, open.y, open.z)) {
            chest.setOpen(false);
        }
    }

    @Override
    public int containerInventoryRevision(PlayerIdentity identity, int containerId) {
        if (containerId == 0) return playerInventoryRevision(identity);
        OpenContainer open = this.openContainers.get(identity.uuid());
        return open != null && open.id == containerId ? open.revision : Integer.MIN_VALUE;
    }

    private boolean isChestViewed(Identifier dimension, int x, int y, int z) {
        for (OpenContainer candidate : this.openContainers.values()) {
            if (candidate.kind == ContainerKind.CHEST && candidate.dimension.equals(dimension)
                    && candidate.x == x && candidate.y == y && candidate.z == z) return true;
        }
        return false;
    }

    @Override
    public int[] containerData(PlayerIdentity identity, int containerId) {
        OpenContainer open = this.openContainers.get(identity.uuid());
        if (open == null || open.id != containerId || open.kind != ContainerKind.FURNACE) return new int[0];
        Dimension dimension = this.world.dimensions().getLoaded(open.dimension);
        BlockEntity entity = dimension == null ? null : dimension.getBlockEntity(open.x, open.y, open.z);
        if (!(entity instanceof FurnaceBlockEntity furnace)) return new int[0];
        return new int[] { furnace.getBurnTime(), furnace.getBurnDuration(),
                furnace.getCookProgress(), furnace.getCookDuration() };
    }

    @Override
    public int playerInventoryRevision(PlayerIdentity identity) {
        return this.inventoryRevisions.getOrDefault(identity.uuid(), 0);
    }

    @Override
    public List<EntityReplicationUpdate> drainEntityUpdates(long serverTick) {
        List<EntityReplicationUpdate> updates = new ArrayList<>(this.players.size() + this.entityNetworkIds.size());
        for (Map.Entry<UUID, EntityPlayer> entry : this.players.entrySet()) {
            Integer networkId = this.playerNetworkIds.get(entry.getKey());
            if (networkId == null) continue;
            EntityPlayer player = entry.getValue();
            updates.add(new EntityReplicationUpdate.Upsert(new NetworkEntitySnapshot(
                    networkId, NetworkEntityTypes.PLAYER, player.getDimensionId().toString(),
                    serverTick + 1, player.x, player.y, player.z,
                    player.motionX, player.motionY, player.motionZ, player.yaw, player.pitch,
                    playerMetadata(player))));
        }

        /* World entities keep a stable network ID for their complete lifetime. The simulation
           already partitions them by chunks; only dimensions with an active player ticket are
           inspected, so this never becomes an all-dimensions/all-save scan. */
        java.util.Set<Entity> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.Set<Identifier> dimensions = new java.util.LinkedHashSet<>();
        for (EntityPlayer player : this.players.values()) dimensions.add(player.getDimensionId());
        for (Identifier dimensionId : dimensions) {
            Dimension dimension = this.world.dimensions().getLoaded(dimensionId);
            if (dimension == null) continue;
            dimension.forEachLoadedEntity(entity -> {
                if (entity.isRemoved()) return;
                seen.add(entity);
                int networkId = this.entityNetworkIds.computeIfAbsent(entity,
                        ignored -> this.nextWorldEntityId++);
                this.worldEntitiesByNetworkId.put(networkId, entity);
                this.worldEntityDimensions.put(networkId, dimensionId);
                NetworkEntitySnapshot snapshot = networkSnapshot(networkId, entity, dimensionId,
                        serverTick + 1);
                if (snapshot != null) updates.add(new EntityReplicationUpdate.Upsert(snapshot));
            });
        }
        var iterator = this.entityNetworkIds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, Integer> tracked = iterator.next();
            if (seen.contains(tracked.getKey())) continue;
            updates.add(new EntityReplicationUpdate.Despawn(tracked.getValue(), 0));
            this.worldEntitiesByNetworkId.remove(tracked.getValue());
            this.worldEntityDimensions.remove(tracked.getValue());
            iterator.remove();
        }
        updates.addAll(this.pendingEntityEvents);
        this.pendingEntityEvents.clear();
        updates.addAll(this.pendingEntityDespawns);
        this.pendingEntityDespawns.clear();
        return updates;
    }

    @Override
    public List<ChunkBlockChanges> drainBlockChanges() {
        if (this.pendingBlockChanges.isEmpty()) return List.of();
        List<ChunkBlockChanges> result = List.copyOf(this.pendingBlockChanges);
        this.pendingBlockChanges.clear();
        return result;
    }

    @Override
    public List<WorldSoundEvent> drainSoundEvents() {
        if (this.pendingSoundEvents.isEmpty()) return List.of();
        List<WorldSoundEvent> result = List.copyOf(this.pendingSoundEvents);
        this.pendingSoundEvents.clear();
        return result;
    }

    @Override
    public List<BlockEntityReplicationUpdate> drainBlockEntityUpdates() {
        if (this.pendingBlockEntityUpdates.isEmpty()) return List.of();
        List<BlockEntityReplicationUpdate> result = List.copyOf(this.pendingBlockEntityUpdates);
        this.pendingBlockEntityUpdates.clear();
        return result;
    }

    private void collectBlockEntityUpdates(Dimension dimension) {
        for (BlockPos pos : dimension.drainNetworkBlockEntityMutations()) {
            BlockEntity entity = dimension.getBlockEntity(pos.x(), pos.y(), pos.z());
            if (entity == null) continue; // removals are represented by their block-state update
            var snapshot = ChunkSnapshotEncoder.encodeBlockEntity(entity);
            if (snapshot == null) continue;
            this.pendingBlockEntityUpdates.add(new BlockEntityReplicationUpdate(
                    dimension.getDimensionId().toString(), pos.x() >> ChunkSection.SHIFT,
                    pos.z() >> ChunkSection.SHIFT, snapshot));
        }
    }

    private void configureNetworkDimension(Dimension dimension) {
        dimension.enableNetworkBlockMutationTracking();
        if (this.networkConfiguredDimensions.put(dimension, Boolean.TRUE) == null) {
            dimension.setSoundManager(new ReplicatingSoundSink(dimension.getDimensionId().toString()));
        }
    }

    private final class ReplicatingSoundSink implements WorldSoundSink {
        private final String dimension;
        private ReplicatingSoundSink(String dimension) { this.dimension = dimension; }
        private void emit(WorldSoundType type, int data, double x, double y, double z) {
            pendingSoundEvents.add(new WorldSoundEvent(this.dimension, type, data, x, y, z));
        }
        @Override public void playHit(BlockSoundGroup group, double x, double y, double z) { emit(WorldSoundType.HIT, group.ordinal(), x, y, z); }
        @Override public void playBreak(BlockSoundGroup group, double x, double y, double z) { emit(WorldSoundType.BREAK, group.ordinal(), x, y, z); }
        @Override public void playPlace(BlockSoundGroup group, double x, double y, double z) { emit(WorldSoundType.PLACE, group.ordinal(), x, y, z); }
        @Override public void playComparatorClick(boolean subtract, double x, double y, double z) { emit(WorldSoundType.COMPARATOR_CLICK, subtract ? 1 : 0, x, y, z); }
        @Override public void playLeverClick(boolean powered, double x, double y, double z) { emit(WorldSoundType.LEVER_CLICK, powered ? 1 : 0, x, y, z); }
        @Override public void playExplosion(double x, double y, double z) { emit(WorldSoundType.EXPLOSION, 0, x, y, z); }
        @Override public void playFuse(double x, double y, double z) { emit(WorldSoundType.FUSE, 0, x, y, z); }
        @Override public void playPistonExtend(double x, double y, double z) { emit(WorldSoundType.PISTON_EXTEND, 0, x, y, z); }
        @Override public void playPistonContract(double x, double y, double z) { emit(WorldSoundType.PISTON_CONTRACT, 0, x, y, z); }
        @Override public void playFizz(double x, double y, double z) { emit(WorldSoundType.FIZZ, 0, x, y, z); }
        @Override public void playFluidExtinguish(double x, double y, double z) { emit(WorldSoundType.FLUID_EXTINGUISH, 0, x, y, z); }
        @Override public void playWaterAmbient(double x, double y, double z) { emit(WorldSoundType.WATER_AMBIENT, 0, x, y, z); }
        @Override public void playLavaAmbient(double x, double y, double z) { emit(WorldSoundType.LAVA_AMBIENT, 0, x, y, z); }
        @Override public void playLavaPop(double x, double y, double z) { emit(WorldSoundType.LAVA_POP, 0, x, y, z); }
        @Override public void playIgnite(double x, double y, double z) { emit(WorldSoundType.IGNITE, 0, x, y, z); }
        @Override public void playPortalAmbient(double x, double y, double z) { emit(WorldSoundType.PORTAL_AMBIENT, 0, x, y, z); }
        @Override public void playPortalTrigger(double x, double y, double z) { emit(WorldSoundType.PORTAL_TRIGGER, 0, x, y, z); }
        @Override public void playPortalTravel() { emit(WorldSoundType.PORTAL_TRAVEL, 0, 0, 0, 0); }
        @Override public void playDispenserSuccess(double x, double y, double z) { emit(WorldSoundType.DISPENSER_SUCCESS, 0, x, y, z); }
        @Override public void playDispenserFailure(double x, double y, double z) { emit(WorldSoundType.DISPENSER_FAILURE, 0, x, y, z); }
        @Override public void playBucketEmpty(boolean lava, double x, double y, double z) { emit(WorldSoundType.BUCKET_EMPTY, lava ? 1 : 0, x, y, z); }
        @Override public void playBucketFill(boolean lava, double x, double y, double z) { emit(WorldSoundType.BUCKET_FILL, lava ? 1 : 0, x, y, z); }
        @Override public void playItemFrameRemoveItem(double x, double y, double z) { emit(WorldSoundType.ITEM_FRAME_REMOVE_ITEM, 0, x, y, z); }
        @Override public void playItemFrameBreak(double x, double y, double z) { emit(WorldSoundType.ITEM_FRAME_BREAK, 0, x, y, z); }
        @Override public void playBlockOpen(BlockOpenSound sound, double x, double y, double z) { emit(WorldSoundType.BLOCK_OPEN, sound.ordinal(), x, y, z); }
        @Override public void playBlockClose(BlockOpenSound sound, double x, double y, double z) { emit(WorldSoundType.BLOCK_CLOSE, sound.ordinal(), x, y, z); }
    }

    private NetworkEntitySnapshot networkSnapshot(int networkId, Entity entity,
                                                   Identifier dimension, long revision) {
        int type;
        byte[] metadata;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                if (entity instanceof ItemEntity item) {
                    type = NetworkEntityTypes.ITEM;
                    writeStack(output, toNetworkStack(item.getStack()));
                    output.writeInt(item.getAge());
                    output.writeInt(item.getPickupDelay());
                } else if (entity instanceof FallingBlockEntity falling) {
                    type = NetworkEntityTypes.FALLING_BLOCK;
                    output.writeInt(falling.getBlockId());
                } else if (entity instanceof PrimedTntEntity tnt) {
                    type = NetworkEntityTypes.PRIMED_TNT;
                    output.writeFloat(tnt.getPower());
                    output.writeInt(tnt.getFuse());
                } else if (entity instanceof ItemFrameEntity frame) {
                    type = NetworkEntityTypes.ITEM_FRAME;
                    output.writeInt(frame.getAnchorX());
                    output.writeInt(frame.getAnchorY());
                    output.writeInt(frame.getAnchorZ());
                    output.writeByte(frame.getDirection().faceIndex());
                    output.writeByte(frame.getRotation());
                    writeStack(output, toNetworkStack(frame.getItem()));
                } else if (entity instanceof MinecartEntity minecart) {
                    type = NetworkEntityTypes.MINECART;
                    output.writeFloat(minecart.getDamage());
                    output.writeInt(minecart.getHurtTime());
                    output.writeInt(minecart.getHurtDirection());
                } else {
                    return null;
                }
            }
            metadata = bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode entity " + entity.getClass().getSimpleName(), impossible);
        }
        return new NetworkEntitySnapshot(networkId, type, dimension.toString(), revision,
                entity.x, entity.y, entity.z, entity.motionX, entity.motionY, entity.motionZ,
                entity.yaw, entity.pitch, metadata);
    }

    private static void writeStack(DataOutputStream output, NetworkItemStack stack) throws IOException {
        output.writeInt(stack.itemId());
        output.writeInt(stack.count());
        byte[] components = stack.components();
        output.writeInt(components.length);
        output.write(components);
    }

    private byte[] playerMetadata(EntityPlayer player) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(80);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(player.getGamemode().ordinal());
                output.writeByte(player.getSelectedSlot());
                writeStack(output, toNetworkStack(player.getInventory().get(player.getSelectedSlot())));
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode remote player metadata", impossible);
        }
    }

    @Override
    public EntityActionOutcome handleEntityAction(PlayerIdentity identity,
                                                  EntityActionRequest request, long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        Entity target = this.worldEntitiesByNetworkId.get(request.networkEntityId());
        Identifier targetDimension = this.worldEntityDimensions.get(request.networkEntityId());
        if (player == null || target == null || target.isRemoved() || targetDimension == null
                || !targetDimension.equals(player.getDimensionId())) {
            return EntityActionOutcome.rejected(request.actionId(), "Unknown entity");
        }
        double dx = target.x - player.x;
        double dy = target.y - (player.y + 1.62);
        double dz = target.z - player.z;
        if (dx * dx + dy * dy + dz * dz > 36) {
            return EntityActionOutcome.rejected(request.actionId(), "Entity is out of reach");
        }
        Dimension dimension = this.world.dimensions().getLoaded(player.getDimensionId());
        if (dimension == null) return EntityActionOutcome.rejected(request.actionId(), "Dimension is unavailable");

        boolean changed = false;
        boolean accepted = false;
        ItemStack held = player.getInventory().get(player.getSelectedSlot());
        if (target instanceof ItemFrameEntity frame) {
            switch (request.action()) {
                case ATTACK -> { frame.attack(dimension, player.getGamemode() == Gamemode.CREATIVE); accepted = true; }
                case INTERACT -> {
                    int before = held.getCount();
                    accepted = frame.interact(dimension, held, player.getGamemode() == Gamemode.CREATIVE);
                    changed = accepted && held.getCount() != before;
                }
                case PICK -> {
                    if (player.getGamemode() == Gamemode.CREATIVE) {
                        player.getInventory().set(player.getSelectedSlot(), frame.getPickResult());
                        accepted = changed = true;
                    }
                }
            }
        } else if (target instanceof MinecartEntity minecart) {
            switch (request.action()) {
                case ATTACK -> {
                    boolean pickaxe = held.getItem() instanceof de.skyengine.game.world.item.ToolItem tool
                            && tool.getType() == de.skyengine.game.world.item.ToolType.PICKAXE;
                    minecart.attack(dimension, player.getGamemode() == Gamemode.CREATIVE, pickaxe);
                    accepted = true;
                }
                case INTERACT -> accepted = minecart.interact(player);
                case PICK -> {
                    if (player.getGamemode() == Gamemode.CREATIVE) {
                        Item item = Items.get(Identifier.of("minecart"));
                        if (item != null) {
                            player.getInventory().set(player.getSelectedSlot(), new ItemStack(item, 1));
                            accepted = changed = true;
                        }
                    }
                }
            }
        }
        if (!accepted) return EntityActionOutcome.rejected(request.actionId(), "Entity action was rejected");
        if (changed) bumpInventoryRevision(identity.uuid());
        emitPlayerEvent(identity.uuid(), EntityEventTypes.SWING, 0);
        return new EntityActionOutcome(request.actionId(), true, "", changed);
    }

    private void updateEating(UUID identity, EntityPlayer player, boolean using) {
        ItemStack held = player.getInventory().get(player.getSelectedSlot());
        if (!using || player.getGamemode() != Gamemode.SURVIVAL || player.isDead()
                || player.getFoodLevel() >= EntityPlayer.MAX_FOOD
                || !(held.getItem() instanceof FoodItem food)) {
            this.eatingTicks.remove(identity);
            return;
        }
        int ticks = this.eatingTicks.getOrDefault(identity, 0) + 1;
        if (ticks < 32) {
            this.eatingTicks.put(identity, ticks);
            return;
        }
        this.eatingTicks.remove(identity);
        player.eat(food.getNutrition(), food.getSaturation());
        held.setCount(held.getCount() - 1);
        if (held.isEmpty()) player.getInventory().set(player.getSelectedSlot(), ItemStack.EMPTY);
        else player.getInventory().setChanged();
        bumpInventoryRevision(identity);
    }

    private void pickupItems(EntityPlayer player, Dimension dimension) {
        double centerY = player.y + 0.9;
        final boolean[] changed = {false};
        dimension.forEachEntityNearby(player.x, player.z, 1, entity -> {
            if (!(entity instanceof de.skyengine.game.entity.ItemEntity item)
                    || item.isRemoved() || item.getPickupDelay() > 0) return;
            double dx = item.x - player.x, dy = item.y - centerY, dz = item.z - player.z;
            if (dx * dx + dy * dy + dz * dz > 1.4 * 1.4) return;
            int before = item.getStack().getCount();
            ItemStack remaining = player.getInventory().insert(item.getStack());
            if (remaining.isEmpty()) item.remove();
            else item.getStack().setCount(remaining.getCount());
            if (remaining.getCount() < before) changed[0] = true;
        });
        if (changed[0]) {
            bumpInventoryRevision(player.getUuid());
            emitPlayerEvent(player.getUuid(), EntityEventTypes.PICKUP, 0);
        }
    }

    @Override
    public InventoryActionOutcome handleInventoryAction(PlayerIdentity identity,
                                                        InventoryActionRequest request, long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        OpenContainer open = this.openContainers.get(identity.uuid());
        ItemStorage inventory = request.containerId() == 0 && player != null ? player.getInventory()
                : open != null && open.id == request.containerId() ? open.combined : null;
        if (player == null || inventory == null) {
            return InventoryActionOutcome.rejected(request.transactionId(), "Unknown inventory");
        }
        if (open != null && open.id == request.containerId() && !validOpenContainer(player, open)) {
            return inventoryOutcome(identity, request.containerId(), request.transactionId(), false,
                    "Container is no longer reachable");
        }
        int source = request.sourceSlot();
        if (source >= inventory.size() || request.targetSlot() >= inventory.size()) {
            return InventoryActionOutcome.rejected(request.transactionId(), "Slot outside inventory");
        }
        ItemStack carried = this.carriedStacks.getOrDefault(identity.uuid(), ItemStack.EMPTY);
        SlotAcceptance accepts = (slot, stack) -> acceptsStack(open, slot, stack);
        int craftingResultSlot = open != null && open.id == request.containerId()
                && open.crafting != null ? open.blockInventory.size() - 1 : -1;
        boolean changed = source == craftingResultSlot
                ? handleCraftingResult(player, open, request, carried, identity.uuid())
                : switch (request.action()) {
            case PICKUP -> source >= 0 && pickup(inventory, source, request.button(), carried, accepts,
                    stack -> this.carriedStacks.put(identity.uuid(), stack));
            case QUICK_MOVE -> source >= 0 && quickMove(inventory, source,
                    request.containerId() == 0 ? 0 : open.blockInventory.size(),
                    open != null && open.crafting != null
                            ? open.blockInventory.size() - 1 : open == null ? 0 : open.blockInventory.size(),
                    request.button(), accepts);
            case SWAP -> source >= 0 && request.targetSlot() >= 0
                    && swap(inventory, source, request.targetSlot(), accepts);
            case DROP -> dropInventoryStack(player, inventory, source, request.button(), carried, identity.uuid());
            case CLONE -> cloneCreative(player, request, carried, identity.uuid());
            case DRAG -> source >= 0 && drag(inventory, source, request.targetSlot(),
                    request.button(), carried, accepts,
                    stack -> this.carriedStacks.put(identity.uuid(), stack));
        };
        if (!changed) return inventoryOutcome(identity, request.containerId(), request.transactionId(), false,
                "Inventory action was rejected");
        if (request.containerId() == 0) {
            bumpInventoryRevision(identity.uuid());
        } else {
            open.revision++;
            open.contentHash = storageHash(open.combined);
        }
        return inventoryOutcome(identity, request.containerId(), request.transactionId(), true, "");
    }

    private boolean validOpenContainer(EntityPlayer player, OpenContainer open) {
        if (open.kind == ContainerKind.PLAYER_INVENTORY) return true;
        if (!player.getDimensionId().equals(open.dimension)) return false;
        double dx = open.x + 0.5 - player.x;
        double dy = open.y + 0.5 - (player.y + 1.62);
        double dz = open.z + 0.5 - player.z;
        if (dx * dx + dy * dy + dz * dz > 64) return false;
        Dimension dimension = this.world.dimensions().getLoaded(open.dimension);
        if (dimension == null) return false;
        if (open.kind == ContainerKind.CRAFTING) {
            var block = Blocks.getState(dimension.getBlock(open.x, open.y, open.z)).getBlock();
            return block.getCraftingWidth() > 0 && block.getCraftingHeight() > 0;
        }
        BlockEntity entity = dimension.getBlockEntity(open.x, open.y, open.z);
        return switch (open.kind) {
            case CHEST -> entity instanceof ChestBlockEntity;
            case HOPPER -> entity instanceof HopperBlockEntity;
            case DISPENSER -> entity instanceof DispenserBlockEntity;
            case FURNACE -> entity instanceof FurnaceBlockEntity;
            case CRAFTING, PLAYER_INVENTORY -> true;
        };
    }

    private boolean handleCraftingResult(EntityPlayer player, OpenContainer open,
                                         InventoryActionRequest request, ItemStack carried, UUID identity) {
        ItemStack preview = open.crafting.output().get(0);
        if (preview.isEmpty()) return false;
        if (request.action() == InventoryActionRequest.Action.QUICK_MOVE) {
            return open.crafting.craftAll() > 0;
        }
        if (request.action() == InventoryActionRequest.Action.CLONE
                && player.getGamemode() == Gamemode.CREATIVE && carried.isEmpty()) {
            preview.setCount(preview.getMaxStackSize());
            this.carriedStacks.put(identity, preview);
            return true;
        }
        if (request.action() == InventoryActionRequest.Action.SWAP && request.targetSlot() >= 0
                && request.targetSlot() < open.combined.size()) {
            ItemStack target = open.combined.get(request.targetSlot());
            if (!target.isEmpty() && (!target.canStackWith(preview)
                    || target.getCount() + preview.getCount() > target.getMaxStackSize())) return false;
            ItemStack made = open.crafting.output().extract(0, preview.getCount());
            if (target.isEmpty()) open.combined.set(request.targetSlot(), made);
            else { target.setCount(target.getCount() + made.getCount()); open.combined.setChanged(); }
            return true;
        }
        if (request.action() != InventoryActionRequest.Action.PICKUP) return false;
        if (!carried.isEmpty() && (!carried.canStackWith(preview)
                || carried.getCount() + preview.getCount() > carried.getMaxStackSize())) return false;
        ItemStack made = open.crafting.output().extract(0, preview.getCount());
        if (carried.isEmpty()) this.carriedStacks.put(identity, made);
        else carried.setCount(carried.getCount() + made.getCount());
        return true;
    }

    private interface StackSetter { void set(ItemStack stack); }
    private interface SlotAcceptance { boolean accepts(int slot, ItemStack stack); }

    private static boolean acceptsStack(OpenContainer open, int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty() || open == null || slot >= open.blockInventory.size()) return true;
        if (open.crafting != null && slot == open.blockInventory.size() - 1) return false;
        if (open.kind != ContainerKind.FURNACE) return true;
        return switch (slot) {
            case FurnaceBlockEntity.INPUT -> RecipeManager.get().findProcessing(
                    RecipeManager.FURNACE, List.of(stack)) != null;
            case FurnaceBlockEntity.FUEL -> RecipeManager.get().fuels().burnTime(
                    RecipeManager.SOLID_FUEL, stack) > 0;
            case FurnaceBlockEntity.OUTPUT -> false;
            default -> false;
        };
    }

    private static boolean pickup(ItemStorage inventory,
                                  int slot, int button, ItemStack carried, SlotAcceptance accepts,
                                  StackSetter setCarried) {
        ItemStack inSlot = inventory.get(slot);
        if (carried.isEmpty()) {
            if (inSlot.isEmpty()) return false;
            int amount = button == 1 ? (inSlot.getCount() + 1) / 2 : inSlot.getCount();
            setCarried.set(inventory.extract(slot, amount));
            return true;
        }
        if (!accepts.accepts(slot, carried)) {
            if (!inSlot.canStackWith(carried)) return false;
            int amount = Math.min(carried.getMaxStackSize() - carried.getCount(), inSlot.getCount());
            if (amount <= 0) return false;
            ItemStack taken = inventory.extract(slot, amount);
            carried.setCount(carried.getCount() + taken.getCount());
            return !taken.isEmpty();
        }
        if (inSlot.isEmpty()) {
            int amount = button == 1 ? 1 : carried.getCount();
            inventory.set(slot, carried.split(amount));
            if (carried.isEmpty()) setCarried.set(ItemStack.EMPTY);
            return true;
        }
        if (inSlot.canStackWith(carried) && inSlot.getCount() < inSlot.getMaxStackSize()) {
            int amount = Math.min(button == 1 ? 1 : carried.getCount(),
                    inSlot.getMaxStackSize() - inSlot.getCount());
            inSlot.setCount(inSlot.getCount() + amount);
            carried.setCount(carried.getCount() - amount);
            inventory.setChanged();
            if (carried.isEmpty()) setCarried.set(ItemStack.EMPTY);
            return amount > 0;
        }
        inventory.set(slot, carried);
        setCarried.set(inSlot);
        return true;
    }

    private static boolean quickMove(ItemStorage inventory, int source, int containerSlots,
                                     int insertableContainerSlots, int requestedAmount,
                                     SlotAcceptance accepts) {
        ItemStack stack = inventory.get(source);
        if (stack.isEmpty()) return false;
        int start, end;
        if (containerSlots > 0) {
            start = source < containerSlots ? containerSlots : 0;
            end = source < containerSlots ? inventory.size() : insertableContainerSlots;
        } else {
            start = source < 9 ? 9 : 0;
            end = source < 9 ? inventory.size() : 9;
        }
        int amount = requestedAmount <= 0 ? stack.getCount() : Math.min(requestedAmount, stack.getCount());
        ItemStack moving = inventory.extract(source, amount);
        int before = moving.getCount();
        for (int pass = 0; pass < 2 && !moving.isEmpty(); pass++) {
            for (int slot = start; slot < end && !moving.isEmpty(); slot++) {
                if (!accepts.accepts(slot, moving)) continue;
                ItemStack target = inventory.get(slot);
                if (pass == 0 && target.canStackWith(moving)) {
                    int merged = Math.min(moving.getCount(), target.getMaxStackSize() - target.getCount());
                    target.setCount(target.getCount() + merged); moving.setCount(moving.getCount() - merged);
                    inventory.setChanged();
                } else if (pass == 1 && target.isEmpty()) {
                    inventory.set(slot, moving.split(moving.getMaxStackSize()));
                }
            }
        }
        if (!moving.isEmpty()) inventory.set(source, moving);
        return moving.getCount() != before;
    }

    /** Mouse-drag primitive: carried -> slot, or source -> target for the wheel tweak. */
    private static boolean drag(ItemStorage inventory, int source, int target, int amount,
                                ItemStack carried, SlotAcceptance accepts, StackSetter setCarried) {
        if (amount <= 0) return false;
        if (target < 0) {
            if (carried.isEmpty()) return false;
            if (!accepts.accepts(source, carried)) return false;
            ItemStack existing = inventory.get(source);
            if (!existing.isEmpty() && !existing.canStackWith(carried)) return false;
            int space = existing.isEmpty() ? carried.getMaxStackSize()
                    : existing.getMaxStackSize() - existing.getCount();
            int moved = Math.min(amount, Math.min(space, carried.getCount()));
            if (moved <= 0) return false;
            if (existing.isEmpty()) inventory.set(source, carried.split(moved));
            else {
                existing.setCount(existing.getCount() + moved);
                carried.setCount(carried.getCount() - moved);
                inventory.setChanged();
            }
            if (carried.isEmpty()) setCarried.set(ItemStack.EMPTY);
            return true;
        }
        if (target >= inventory.size() || source == target) return false;
        ItemStack from = inventory.get(source), to = inventory.get(target);
        if (!accepts.accepts(target, from)) return false;
        if (from.isEmpty() || (!to.isEmpty() && !to.canStackWith(from))) return false;
        int space = to.isEmpty() ? from.getMaxStackSize() : to.getMaxStackSize() - to.getCount();
        int moved = Math.min(amount, Math.min(space, from.getCount()));
        if (moved <= 0) return false;
        ItemStack taken = inventory.extract(source, moved);
        if (to.isEmpty()) inventory.set(target, taken);
        else { to.setCount(to.getCount() + taken.getCount()); inventory.setChanged(); }
        return true;
    }

    private static boolean swap(ItemStorage inventory, int source, int target, SlotAcceptance accepts) {
        if (source == target) return false;
        ItemStack a = inventory.get(source), b = inventory.get(target);
        if (!accepts.accepts(source, b) || !accepts.accepts(target, a)) return false;
        inventory.set(source, b); inventory.set(target, a);
        return true;
    }

    private boolean dropInventoryStack(EntityPlayer player, ItemStorage inventory, int source, int button,
                                       ItemStack carried, UUID identity) {
        ItemStack dropped;
        if (source < 0) {
            if (carried.isEmpty()) return false;
            dropped = carried.split(button == 1 ? carried.getCount() : 1);
            if (carried.isEmpty()) this.carriedStacks.put(identity, ItemStack.EMPTY);
        } else {
            ItemStack stack = inventory.get(source);
            if (stack.isEmpty()) return false;
            dropped = inventory.extract(source, button == 1 ? stack.getCount() : 1);
        }
        Dimension dimension = this.world.dimensions().getLoaded(player.getDimensionId());
        if (dimension != null) dimension.throwItem(player, dropped);
        return true;
    }

    private boolean cloneCreative(EntityPlayer player, InventoryActionRequest request,
                                  ItemStack carried, UUID identity) {
        if (player.getGamemode() != Gamemode.CREATIVE) return false;
        ItemStack offered = fromNetworkStack(request.offeredStack());
        if (offered.isEmpty()) return false;
        // Synthetic pick-block action: put the authoritative full stack straight into the
        // requested hotbar slot. The client only proposes a registry-validated item stack.
        if (request.sourceSlot() == -3) {
            if (request.targetSlot() < 0 || request.targetSlot() > 8) return false;
            offered.setCount(offered.getMaxStackSize());
            player.getInventory().set(request.targetSlot(), offered);
            return true;
        }
        if (request.sourceSlot() == -2) {
            if (request.button() == 2) {
                offered.setCount(offered.getMaxStackSize());
                player.getInventory().insert(offered);
                return true;
            }
            if (carried.isEmpty()) {
                this.carriedStacks.put(identity, offered.copy());
            } else if (carried.getItem() != offered.getItem()) {
                this.carriedStacks.put(identity, ItemStack.EMPTY);
            } else {
                int count = carried.getCount() + (request.button() == 1 ? -1 : 1);
                if (count <= 0) this.carriedStacks.put(identity, ItemStack.EMPTY);
                else carried.setCount(Math.min(count, carried.getMaxStackSize()));
            }
            return true;
        }
        offered.setCount(offered.getMaxStackSize());
        this.carriedStacks.put(identity, offered);
        return true;
    }

    private ItemStack fromNetworkStack(NetworkItemStack network) {
        if (network == null || network.count() == 0) return ItemStack.EMPTY;
        if (network.itemId() <= 0 || network.itemId() >= this.networkItems.size()) return ItemStack.EMPTY;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(network.components()))) {
            ItemStack stack = ItemStack.load(DataTagIO.read(input));
            if (stack.isEmpty() || stack.getItem() != this.networkItems.get(network.itemId())) return ItemStack.EMPTY;
            stack.setCount(Math.min(network.count(), stack.getMaxStackSize()));
            return stack;
        } catch (IOException invalid) {
            return ItemStack.EMPTY;
        }
    }

    private InventoryActionOutcome inventoryOutcome(PlayerIdentity identity, int containerId, long transactionId,
                                                    boolean accepted, String message) {
        InventoryActionOutcome snapshot = containerInventory(identity, containerId);
        return new InventoryActionOutcome(transactionId, accepted, message, snapshot.containerId(),
                snapshot.revision(), snapshot.content(), snapshot.carried());
    }

    private static int storageHash(ItemStorage storage) {
        int hash = 1;
        for (int slot = 0; slot < storage.size(); slot++) {
            ItemStack stack = storage.get(slot);
            hash = 31 * hash + (stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem()));
            hash = 31 * hash + (stack.isEmpty() ? 0 : stack.getCount());
            hash = 31 * hash + (stack.isEmpty() ? 0 : stack.getDamage());
        }
        return hash;
    }

    private NetworkItemStack toNetworkStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return NetworkItemStack.empty();
        Integer itemId = this.itemNetworkIds.get(stack.getItem());
        if (itemId == null) throw new IllegalStateException("Unregistered item " + stack.getItem());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                DataTagIO.write(stack.save(), output);
            }
            return new NetworkItemStack(itemId, stack.getCount(), bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode item stack", impossible);
        }
    }

    @Override
    public BlockActionOutcome handleBlockAction(PlayerIdentity identity, PlayerStateSnapshot state,
                                                 BlockActionRequest request, long serverTick) {
        EntityPlayer player = this.players.get(identity.uuid());
        if (player == null || player.isDead() || !player.getGamemode().interactsWithWorld()) {
            return BlockActionOutcome.rejected(request.actionId(), "Game mode cannot interact");
        }
        Dimension dimension = this.world.dimensions().getLoaded(player.getDimensionId());
        if (dimension == null || !collisionAreaReady(dimension, player)) {
            return BlockActionOutcome.rejected(request.actionId(), "Chunk is not ready");
        }
        if (request.y() < 0 || request.y() >= Chunk.HEIGHT) {
            return BlockActionOutcome.rejected(request.actionId(), "Block position is outside the world");
        }
        int current = dimension.getBlock(request.x(), request.y(), request.z());
        if (current != request.expectedStateId()) {
            return BlockActionOutcome.rejected(request.actionId(), "Block state changed",
                    blockCorrections(dimension, request));
        }
        if ((request.action() == BlockActionRequest.Action.PLACE
                || request.action() == BlockActionRequest.Action.INTERACT)
                && request.expectedTargetStateId() >= 0) {
            int[] target = placementTarget(dimension, request);
            if (target[1] < 0 || target[1] >= Chunk.HEIGHT) {
                return BlockActionOutcome.rejected(request.actionId(), "Placement target is outside the world",
                        blockCorrections(dimension, request));
            }
            if (dimension.getBlock(target[0], target[1], target[2]) != request.expectedTargetStateId()) {
                return BlockActionOutcome.rejected(request.actionId(), "Placement target changed",
                        blockCorrections(dimension, request));
            }
        }
        BlockActionOutcome outcome = switch (request.action()) {
            case START_BREAK -> startBreaking(identity, player, dimension, request);
            case CANCEL_BREAK -> {
                this.mining.remove(identity.uuid());
                yield new BlockActionOutcome(request.actionId(), true, "", List.of(), false);
            }
            case FINISH_BREAK -> finishBreaking(identity, player, dimension, request);
            case PLACE, INTERACT -> useOrPlace(identity, player, dimension, request);
        };
        if (outcome.accepted() && request.action() != BlockActionRequest.Action.CANCEL_BREAK) {
            emitPlayerEvent(identity.uuid(), EntityEventTypes.SWING, 0);
        }
        if (!outcome.accepted() && outcome.corrections().isEmpty()) {
            return BlockActionOutcome.rejected(request.actionId(), outcome.message(),
                    blockCorrections(dimension, request));
        }
        return outcome;
    }

    private static List<AuthoritativeBlockCorrection> blockCorrections(
            Dimension dimension, BlockActionRequest request) {
        List<AuthoritativeBlockCorrection> result = new ArrayList<>(2);
        String id = dimension.getDimensionId().toString();
        result.add(new AuthoritativeBlockCorrection(id, request.x(), request.y(), request.z(),
                dimension.getBlock(request.x(), request.y(), request.z())));
        if (request.action() == BlockActionRequest.Action.PLACE
                || request.action() == BlockActionRequest.Action.INTERACT) {
            int[] target = placementTarget(dimension, request);
            if ((target[0] != request.x() || target[1] != request.y() || target[2] != request.z())
                    && target[1] >= 0 && target[1] < Chunk.HEIGHT) {
                result.add(new AuthoritativeBlockCorrection(id, target[0], target[1], target[2],
                        dimension.getBlock(target[0], target[1], target[2])));
            }
        }
        return List.copyOf(result);
    }

    private static int[] placementTarget(Dimension dimension, BlockActionRequest request) {
        int x = request.x(), y = request.y(), z = request.z();
        if (!Blocks.getState(dimension.getBlock(x, y, z)).getBlock().isReplaceable()) {
            Direction face = PlayerBlockActions.directionFromFace(request.face());
            x += face.offsetX(); y += face.offsetY(); z += face.offsetZ();
        }
        return new int[]{x, y, z};
    }

    private void emitPlayerEvent(UUID identity, int eventId, int data) {
        Integer networkId = this.playerNetworkIds.get(identity);
        if (networkId != null) {
            this.pendingEntityEvents.add(new EntityReplicationUpdate.Event(networkId, eventId, data));
        }
    }

    @Override
    public void playerSwing(PlayerIdentity identity, int entityId) {
        emitPlayerEvent(identity.uuid(), EntityEventTypes.SWING, 0);
    }

    private BlockActionOutcome startBreaking(PlayerIdentity identity, EntityPlayer player,
                                             Dimension dimension, BlockActionRequest request) {
        float initial = PlayerBlockActions.destroyProgress(player, Blocks.getState(request.expectedStateId()));
        this.mining.put(identity.uuid(), new MiningState(request.x(), request.y(), request.z(),
                request.expectedStateId(), initial));
        if (!player.getGamemode().isInstantBreak() && initial < 1F) {
            return new BlockActionOutcome(request.actionId(), true, "", List.of(), false);
        }
        return mutateBreak(identity, player, dimension, request, false);
    }

    private BlockActionOutcome finishBreaking(PlayerIdentity identity, EntityPlayer player,
                                              Dimension dimension, BlockActionRequest request) {
        MiningState active = this.mining.remove(identity.uuid());
        if (active == null || active.x() != request.x() || active.y() != request.y()
                || active.z() != request.z() || active.expectedStateId() != request.expectedStateId()) {
            return BlockActionOutcome.rejected(request.actionId(), "No matching break action");
        }
        float finalProgress = active.progress() + PlayerBlockActions.destroyProgress(player,
                Blocks.getState(active.expectedStateId()));
        if (!player.getGamemode().isInstantBreak() && finalProgress < 1F) {
            return BlockActionOutcome.rejected(request.actionId(), "Block breaking is not complete");
        }
        return mutateBreak(identity, player, dimension, request, true);
    }

    private BlockActionOutcome mutateBreak(PlayerIdentity identity, EntityPlayer player,
                                           Dimension dimension, BlockActionRequest request,
                                           boolean applyDurability) {
        Dimension.PlayerBlockChangeResult result = dimension.capturePlayerBlockChanges(() ->
                PlayerBlockActions.breakBlock(dimension, player, request.x(), request.y(), request.z(),
                        Blocks.getState(request.expectedStateId()), applyDurability));
        if (!result.accepted()) return BlockActionOutcome.rejected(request.actionId(), "Block could not be changed");
        if (applyDurability) bumpInventoryRevision(identity.uuid());
        return outcome(request.actionId(), dimension, result.changes(), applyDurability);
    }

    private BlockActionOutcome useOrPlace(PlayerIdentity identity, EntityPlayer player,
                                          Dimension dimension, BlockActionRequest request) {
        if (!request.secondaryUse()) {
            PortalController.Travel portalTravel = this.portalControllers
                    .computeIfAbsent(identity.uuid(), ignored -> new PortalController())
                    .use(dimension, request.x(), request.y(), request.z());
            if (portalTravel != null) {
                transferPlayer(identity, player, dimension, portalTravel);
                return new BlockActionOutcome(request.actionId(), true, "", List.of(), false);
            }
            ContainerOpenData opened = openContainer(identity, player, dimension,
                    request.x(), request.y(), request.z());
            if (opened != null) {
                return new BlockActionOutcome(request.actionId(), true, "", List.of(), false, opened);
            }
        }
        ItemStack held = player.getInventory().get(player.getSelectedSlot());
        boolean inventoryChanged = false;
        if (player.getGamemode() == Gamemode.CREATIVE && request.requestedStateId() >= 0) {
            if (request.requestedStateId() >= BlockRegistry.getStateCount()) {
                return BlockActionOutcome.rejected(request.actionId(), "Unknown requested block state");
            }
            Item item = Items.forBlock(Blocks.getState(request.requestedStateId()).getBlock());
            if (item == null) return BlockActionOutcome.rejected(request.actionId(), "Block has no placeable item");
            held = new ItemStack(item, 1);
            player.getInventory().set(player.getSelectedSlot(), held.copy());
            inventoryChanged = true;
        }
        ItemStack actionStack = held;
        Direction direction = PlayerBlockActions.directionFromFace(request.face());
        final PlayerBlockActions.UseResult[] use = new PlayerBlockActions.UseResult[1];
        Dimension.PlayerBlockChangeResult result = dimension.capturePlayerBlockChanges(() -> {
            use[0] = PlayerBlockActions.useOrPlace(dimension, player, request.x(), request.y(), request.z(),
                    direction, request.relativeHitX(), request.relativeHitY(), request.relativeHitZ(), actionStack);
            return use[0].accepted();
        });
        if (!result.accepted()) return BlockActionOutcome.rejected(request.actionId(), "Block use was rejected");
        if (player.getGamemode() == Gamemode.SURVIVAL || inventoryChanged) {
            bumpInventoryRevision(identity.uuid());
            inventoryChanged = true;
        }
        return outcome(request.actionId(), dimension, result.changes(), inventoryChanged);
    }

    private ContainerOpenData openContainer(PlayerIdentity identity, EntityPlayer player,
                                            Dimension dimension, int x, int y, int z) {
        var block = Blocks.getState(dimension.getBlock(x, y, z)).getBlock();
        if (block.getCraftingWidth() > 0 && block.getCraftingHeight() > 0
                && block.getCraftingRecipeType() != null) {
            OpenContainer previous = this.openContainers.get(identity.uuid());
            if (previous != null) closeContainer(identity, previous.id);
            CraftingMenu crafting = new CraftingMenu(block.getCraftingWidth(), block.getCraftingHeight(),
                    block.getCraftingRecipeType(), player.getInventory(),
                    stack -> dimension.throwItem(player, stack));
            ItemStorage craftingSlots = new CompoundItemStorage(crafting.input(), crafting.output());
            ItemStorage combined = new CompoundItemStorage(craftingSlots, player.getInventory());
            int id = this.nextContainerId++;
            if (this.nextContainerId <= 0) this.nextContainerId = 1;
            OpenContainer open = new OpenContainer(id, ContainerKind.CRAFTING,
                    dimension.getDimensionId(), x, y, z, craftingSlots, combined, crafting);
            this.openContainers.put(identity.uuid(), open);
            return new ContainerOpenData(id, ContainerKind.CRAFTING, craftingSlots.size(),
                    block.getCraftingHeight(), dimension.getDimensionId().toString(), x, y, z);
        }
        BlockEntity entity = dimension.getBlockEntity(x, y, z);
        ItemStorage storage;
        ContainerKind kind;
        int rows = 0;
        if (entity instanceof ChestBlockEntity chest) {
            storage = chest.getCombinedInventory();
            kind = ContainerKind.CHEST;
            rows = storage.size() / 9;
        } else if (entity instanceof HopperBlockEntity hopper) {
            storage = hopper.getInventory();
            kind = ContainerKind.HOPPER;
        } else if (entity instanceof DispenserBlockEntity dispenser) {
            storage = dispenser.getInventory();
            kind = ContainerKind.DISPENSER;
        } else if (entity instanceof FurnaceBlockEntity furnace) {
            storage = furnace.getInventory();
            kind = ContainerKind.FURNACE;
        } else {
            return null;
        }
        OpenContainer previous = this.openContainers.get(identity.uuid());
        if (previous != null) closeContainer(identity, previous.id);
        if (entity instanceof ChestBlockEntity chest) chest.setOpen(true);
        int id = this.nextContainerId++;
        if (this.nextContainerId <= 0) this.nextContainerId = 1;
        OpenContainer open = new OpenContainer(id, kind, dimension.getDimensionId(), x, y, z,
                storage, player.getInventory());
        this.openContainers.put(identity.uuid(), open);
        return new ContainerOpenData(id, kind, storage.size(), rows,
                dimension.getDimensionId().toString(), x, y, z);
    }

    /** Server-owned dimension transfer. Chunk interest then streams the normal target world. */
    private void transferPlayer(PlayerIdentity identity, EntityPlayer player, Dimension source,
                                PortalController.Travel travel) {
        var targetDefinition = WorldgenRegistries.DIMENSIONS.get(travel.targetDimension());
        PortalDefinition portal = WorldgenRegistries.PORTALS.get(travel.portalType());
        if (targetDefinition == null || portal == null) return;
        int targetX = travel.x(), targetY = travel.y(), targetZ = travel.z();
        Direction.Axis sourceAxis = null;
        Identifier sourceDimension = source.getDimensionId();
        String sourcePortalId = null;
        String linkedTargetPortalId = null;
        if (portal.linkPolicy() == PortalDefinition.LinkPolicy.NETHER) {
            NetherPortalShape.Shape sourceShape = NetherPortalShape.find(
                    source, travel.x(), travel.y(), travel.z(), true);
            if (sourceShape != null) {
                PortalIndex.Entry sourceEntry = source.getPortalIndex().add(travel.portalType(), sourceShape);
                sourcePortalId = sourceEntry.id();
                sourceAxis = sourceShape.axis();
                targetX = PortalCoordinates.scale(sourceEntry.centerX(), source.getEnvironment(),
                        targetDefinition.environment());
                targetY = sourceShape.bottomY();
                targetZ = PortalCoordinates.scale(sourceEntry.centerZ(), source.getEnvironment(),
                        targetDefinition.environment());
                PortalLinks.Endpoint linked = source.getPortalLinks().linked(
                        travel.portalType(), sourceDimension, sourcePortalId);
                if (linked != null && linked.dimension().equals(travel.targetDimension().toString())) {
                    linkedTargetPortalId = linked.portalId();
                }
            } else {
                targetX = PortalCoordinates.scale(targetX, source.getEnvironment(), targetDefinition.environment());
                targetZ = PortalCoordinates.scale(targetZ, source.getEnvironment(), targetDefinition.environment());
            }
        }
        if (player.getVehicle() != null) player.stopRiding(source);
        OpenContainer open = this.openContainers.get(identity.uuid());
        if (open != null) closeContainer(identity, open.id);
        DimensionManager.DimensionTicket next = this.world.acquireDimension(travel.targetDimension(),
                DimensionManager.TicketType.PLAYER, identity.uuid());
        Dimension target = next.dimension();
        configureNetworkDimension(target);
        target.getChunkManager().setRenderDistance(this.config.viewDistance() + NETWORK_LOAD_HALO);
        target.setSimulationDistance(this.config.simulationDistance());
        DimensionManager.DimensionTicket previous = this.playerTickets.put(identity.uuid(), next);
        if (previous != null) previous.close();
        PortalIndex.Entry indexedPortal = null;
        if (portal.linkPolicy() == PortalDefinition.LinkPolicy.NETHER) {
            if (linkedTargetPortalId != null) {
                indexedPortal = target.getPortalIndex().byId(linkedTargetPortalId);
                if (indexedPortal == null && sourcePortalId != null) {
                    target.getPortalLinks().unlink(travel.portalType(), sourceDimension, sourcePortalId);
                }
            }
            if (indexedPortal == null) {
                indexedPortal = findAvailablePortal(target, travel.portalType(), targetX, targetY, targetZ);
            }
        }
        int loadX = indexedPortal == null ? targetX : indexedPortal.x();
        int loadZ = indexedPortal == null ? targetZ : indexedPortal.z();
        int provisionalY = indexedPortal == null
                ? Math.clamp(targetY > 0 ? targetY : target.getGenerator().sampleHeight(loadX, loadZ) + 2,
                2, Chunk.HEIGHT - 2)
                : Math.clamp(indexedPortal.y(), 2, Chunk.HEIGHT - 2);
        player.setDimensionId(travel.targetDimension());
        player.setPosition(loadX + 0.5, provisionalY, loadZ + 0.5);
        player.motionX = player.motionY = player.motionZ = 0;
        player.resetFallDistance();
        player.snapPrevToCurrent();
        this.portalControllers.get(identity.uuid()).lockUntilExit();
        if (portal.linkPolicy() != PortalDefinition.LinkPolicy.NETHER) {
            this.pendingSimplePortalArrivals.put(identity.uuid(), new PendingSimplePortalArrival(
                    targetX, travel.y(), targetZ, travel.portalType(), true));
        } else {
            this.pendingNetherPortalArrivals.put(identity.uuid(), new PendingNetherPortalArrival(
                    targetX, targetY, targetZ, travel.portalType(), sourceAxis,
                    sourceDimension, sourcePortalId, indexedPortal));
        }
        this.pendingSoundEvents.add(new WorldSoundEvent(travel.targetDimension().toString(),
                WorldSoundType.PORTAL_TRAVEL, 0, player.x, player.y, player.z));
        this.mining.remove(identity.uuid());
        this.eatingTicks.remove(identity.uuid());
    }

    /** Completes SIMPLE portal travel only after the authoritative target collision area exists. */
    private void finalizeSimplePortalArrival(EntityPlayer player, Dimension dimension) {
        PendingSimplePortalArrival arrival = this.pendingSimplePortalArrivals.get(player.getUuid());
        if (arrival == null || !arrivalAreaReady(dimension, arrival.x(), arrival.z())) return;

        int portalY = findSafePortalY(dimension, arrival.x(), arrival.z(), arrival.portalType());
        if (portalY >= 1) {
            finishSimplePortalArrival(player, arrival.x() + 1.5, portalY, arrival.z() + 0.5);
            return;
        }
        boolean createMiningArrival = arrival.createReturnPortal()
                && Identifier.of("mining_portal").equals(arrival.portalType())
                && WorldgenRegistries.MINING.equals(dimension.getDimensionId());
        if (createMiningArrival) {
            int floorY = findArrivalFloor(dimension, arrival.x(), arrival.z());
            for (int x = arrival.x() - 1; x <= arrival.x() + 1; x++) {
                for (int z = arrival.z() - 1; z <= arrival.z() + 1; z++) {
                    dimension.setBlock(x, floorY, z, Blocks.OBSIDIAN);
                    dimension.setBlock(x, floorY + 1, z, Blocks.AIR);
                    dimension.setBlock(x, floorY + 2, z, Blocks.AIR);
                }
            }
            int feetY = floorY + 1;
            dimension.setBlock(arrival.x(), feetY, arrival.z(), Blocks.MINING_PORTAL);
            finishSimplePortalArrival(player, arrival.x() + 1.5, feetY, arrival.z() + 0.5);
            return;
        }

        int[] safe = findSafeArrival(dimension, arrival.x(), arrival.y(), arrival.z());
        finishSimplePortalArrival(player, safe[0] + 0.5, safe[1], safe[2] + 0.5);
    }

    private void finishSimplePortalArrival(EntityPlayer player, double x, double y, double z) {
        player.setPosition(x, y, z);
        player.motionX = player.motionY = player.motionZ = 0;
        player.resetFallDistance();
        player.snapPrevToCurrent();
        this.pendingSimplePortalArrivals.remove(player.getUuid());
        this.portalControllers.computeIfAbsent(player.getUuid(), ignored -> new PortalController())
                .lockUntilExit();
    }

    private static boolean arrivalAreaReady(Dimension dimension, int centerX, int centerZ) {
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                Chunk chunk = dimension.getChunkManager().getChunk(
                        x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
                if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return false;
            }
        }
        return true;
    }

    private static int findSafePortalY(Dimension dimension, int x, int z, Identifier portalType) {
        PortalDefinition definition = portalType == null ? null : WorldgenRegistries.PORTALS.get(portalType);
        if (definition == null) return -1;
        for (int y = Chunk.HEIGHT - 2; y >= 1; y--) {
            int state = dimension.getBlock(x, y, z);
            if (!Blocks.getState(state).getBlock().getIdentifier().equals(definition.block())) continue;
            int floor = dimension.getBlock(x, y - 1, z);
            int head = dimension.getBlock(x, y + 1, z);
            if (Blocks.getState(floor).isSolid() && !Blocks.getState(head).isSolid()) return y;
        }
        return -1;
    }

    private static int findArrivalFloor(Dimension dimension, int x, int z) {
        for (int y = Chunk.HEIGHT - 3; y >= 1; y--) {
            var state = Blocks.getState(dimension.getBlock(x, y, z));
            if (state.isSolid() && !state.isFluid()) return y;
        }
        return 64;
    }

    private static int[] findSafeArrival(Dimension dimension, int targetX, int preferredY, int targetZ) {
        int clampedY = Math.clamp(preferredY, 1, Chunk.HEIGHT - 2);
        for (int radius = 0; radius <= 1; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = targetX + dx, z = targetZ + dz;
                    for (int distance = 0; distance < Chunk.HEIGHT; distance++) {
                        int above = clampedY + distance;
                        if (above < Chunk.HEIGHT - 1 && isSafeArrivalCell(dimension, x, above, z)) {
                            return new int[]{x, above, z};
                        }
                        int below = clampedY - distance;
                        if (distance > 0 && below >= 1 && isSafeArrivalCell(dimension, x, below, z)) {
                            return new int[]{x, below, z};
                        }
                    }
                }
            }
        }
        return new int[]{targetX, clampedY, targetZ};
    }

    private static boolean isSafeArrivalCell(Dimension dimension, int x, int feetY, int z) {
        var floor = Blocks.getState(dimension.getBlock(x, feetY - 1, z));
        var feet = Blocks.getState(dimension.getBlock(x, feetY, z));
        var head = Blocks.getState(dimension.getBlock(x, feetY + 1, z));
        return floor.isSolid() && !floor.isFluid()
                && !feet.isSolid() && !feet.isFluid()
                && !head.isSolid() && !head.isFluid();
    }

    private void finalizeNetherPortalArrival(EntityPlayer player, Dimension dimension) {
        PendingNetherPortalArrival arrival = this.pendingNetherPortalArrivals.get(player.getUuid());
        if (arrival == null || !collisionAreaReady(dimension, player)) return;

        PortalIndex.Entry indexed = arrival.indexedPortal();
        if (indexed != null) {
            NetherPortalShape.Shape shape = NetherPortalShape.find(
                    dimension, indexed.x(), indexed.y(), indexed.z(), true);
            if (shape == null && NetherPortalShape.activate(
                    dimension, indexed.x(), indexed.y(), indexed.z())) {
                shape = NetherPortalShape.find(dimension, indexed.x(), indexed.y(), indexed.z(), true);
            }
            if (shape != null) {
                PortalIndex.Entry targetEntry = dimension.getPortalIndex().add(arrival.portalType(), shape);
                pairPortalArrival(dimension, arrival, targetEntry);
                finishNetherPortalArrival(player, targetEntry.centerX(), shape.bottomY(), targetEntry.centerZ());
                return;
            }
            dimension.getPortalIndex().remove(indexed);
            dimension.getPortalLinks().unlink(arrival.portalType(), dimension.getDimensionId(), indexed.id());
            PortalIndex.Entry next = findAvailablePortal(dimension, arrival.portalType(),
                    arrival.x(), arrival.y(), arrival.z());
            if (next != null) {
                moveToPendingNetherCandidate(player, arrival, next);
                return;
            }
            // The invalid indexed portal may have been far away. Move back to the intended
            // scaled destination and let its collision area stream before creating a frame.
            int y = Math.clamp(dimension.getGenerator().sampleHeight(arrival.x(), arrival.z()) + 2,
                    2, Chunk.HEIGHT - 2);
            player.setPosition(arrival.x() + 0.5, y, arrival.z() + 0.5);
            player.snapPrevToCurrent();
            this.pendingNetherPortalArrivals.put(player.getUuid(), new PendingNetherPortalArrival(
                    arrival.x(), arrival.y(), arrival.z(), arrival.portalType(), arrival.portalAxis(),
                    arrival.sourceDimension(), arrival.sourcePortalId(), null));
            return;
        }

        Direction.Axis axis = arrival.portalAxis() == null ? Direction.Axis.X : arrival.portalAxis();
        int[] site = findNetherPortalSite(dimension, arrival.x(), arrival.y(), arrival.z(), axis);
        int minX = axis == Direction.Axis.X ? site[0] - 1 : site[0];
        int minZ = axis == Direction.Axis.Z ? site[2] - 1 : site[2];
        int bottomY = site[1] + 1;
        buildNetherPortal(dimension, minX, bottomY, minZ, axis);
        NetherPortalShape.Shape shape = NetherPortalShape.find(dimension, minX, bottomY, minZ, true);
        if (shape == null) throw new IllegalStateException("Created Nether portal is invalid");
        PortalIndex.Entry targetEntry = dimension.getPortalIndex().add(arrival.portalType(), shape);
        pairPortalArrival(dimension, arrival, targetEntry);
        finishNetherPortalArrival(player, targetEntry.centerX(), shape.bottomY(), targetEntry.centerZ());
    }

    private void moveToPendingNetherCandidate(EntityPlayer player, PendingNetherPortalArrival arrival,
                                               PortalIndex.Entry candidate) {
        player.setPosition(candidate.x() + 0.5, Math.clamp(candidate.y(), 2, Chunk.HEIGHT - 2),
                candidate.z() + 0.5);
        player.motionX = player.motionY = player.motionZ = 0;
        player.snapPrevToCurrent();
        this.pendingNetherPortalArrivals.put(player.getUuid(), new PendingNetherPortalArrival(
                arrival.x(), arrival.y(), arrival.z(), arrival.portalType(), arrival.portalAxis(),
                arrival.sourceDimension(), arrival.sourcePortalId(), candidate));
    }

    private void pairPortalArrival(Dimension dimension, PendingNetherPortalArrival arrival,
                                   PortalIndex.Entry targetEntry) {
        if (arrival.sourcePortalId() == null || arrival.sourceDimension() == null) return;
        dimension.getPortalLinks().pair(arrival.portalType(), arrival.sourceDimension(),
                arrival.sourcePortalId(), dimension.getDimensionId(), targetEntry.id());
    }

    private void finishNetherPortalArrival(EntityPlayer player, double x, double y, double z) {
        player.setPosition(x, y, z);
        player.motionX = player.motionY = player.motionZ = 0;
        player.resetFallDistance();
        player.snapPrevToCurrent();
        this.pendingNetherPortalArrivals.remove(player.getUuid());
        this.portalControllers.computeIfAbsent(player.getUuid(), ignored -> new PortalController())
                .lockUntilExit();
    }

    private static PortalIndex.Entry findAvailablePortal(Dimension dimension, Identifier portalType,
                                                          int x, int y, int z) {
        int radius = PortalCoordinates.searchRadius(dimension.getEnvironment());
        List<PortalIndex.Entry> candidates = dimension.getPortalIndex().candidates(
                portalType, x, y, z, radius,
                entry -> !dimension.getPortalLinks().isLinked(
                        portalType, dimension.getDimensionId(), entry.id()));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static int[] findNetherPortalSite(Dimension dimension, int targetX, int targetY,
                                              int targetZ, Direction.Axis axis) {
        int maxFloor = dimension.getDimensionId().equals(WorldgenRegistries.NETHER)
                ? 120 : Chunk.HEIGHT - 5;
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = targetX + dx, z = targetZ + dz;
                    for (int y = Math.min(maxFloor, Math.max(2, targetY + 16)); y >= 1; y--) {
                        if (portalSiteClear(dimension, x, y, z, axis)) return new int[]{x, y, z};
                    }
                }
            }
        }
        return new int[]{targetX, Math.clamp(targetY, 32, maxFloor), targetZ};
    }

    private static boolean portalSiteClear(Dimension dimension, int centerX, int floorY,
                                           int centerZ, Direction.Axis axis) {
        int sx = axis == Direction.Axis.X ? 1 : 0;
        int sz = axis == Direction.Axis.Z ? 1 : 0;
        int px = axis == Direction.Axis.X ? 0 : 1;
        int pz = axis == Direction.Axis.Z ? 0 : 1;
        for (int w = -2; w <= 1; w++) {
            int x = centerX + sx * w, z = centerZ + sz * w;
            if (!Blocks.getState(dimension.getBlock(x, floorY, z)).isSolid()) return false;
            for (int side = -1; side <= 1; side++) {
                for (int y = 1; y <= 4; y++) {
                    var state = Blocks.getState(dimension.getBlock(
                            x + px * side, floorY + y, z + pz * side));
                    if (state.isSolid() || state.isFluid()) return false;
                }
            }
        }
        return true;
    }

    private static void buildNetherPortal(Dimension dimension, int minX, int bottomY,
                                          int minZ, Direction.Axis axis) {
        int sx = axis == Direction.Axis.X ? 1 : 0;
        int sz = axis == Direction.Axis.Z ? 1 : 0;
        for (int w = -1; w <= 2; w++) {
            dimension.setBlock(minX + sx * w, bottomY - 1, minZ + sz * w, Blocks.OBSIDIAN, false);
            dimension.setBlock(minX + sx * w, bottomY + 3, minZ + sz * w, Blocks.OBSIDIAN, false);
        }
        for (int h = 0; h < 3; h++) {
            dimension.setBlock(minX - sx, bottomY + h, minZ - sz, Blocks.OBSIDIAN, false);
            dimension.setBlock(minX + sx * 2, bottomY + h, minZ + sz * 2, Blocks.OBSIDIAN, false);
            for (int w = 0; w < 2; w++) {
                dimension.setBlock(minX + sx * w, bottomY + h, minZ + sz * w, Blocks.AIR, false);
            }
        }
        if (!NetherPortalShape.activate(dimension, minX, bottomY, minZ)) {
            throw new IllegalStateException("Nether portal frame could not be activated");
        }
    }

    private BlockActionOutcome outcome(long actionId, Dimension dimension,
                                       List<Dimension.BlockMutation> mutations, boolean inventoryChanged) {
        return new BlockActionOutcome(actionId, true, "", chunkChanges(dimension, mutations), inventoryChanged);
    }

    private static List<ChunkBlockChanges> chunkChanges(Dimension dimension,
                                                         List<Dimension.BlockMutation> mutations) {
        if (mutations.isEmpty()) return List.of();
        record ChunkKey(int x, int z) { }
        Map<ChunkKey, LinkedHashMap<Integer, BlockChange>> grouped = new LinkedHashMap<>();
        for (Dimension.BlockMutation mutation : mutations) {
            int chunkX = mutation.x() >> ChunkSection.SHIFT, chunkZ = mutation.z() >> ChunkSection.SHIFT;
            BlockChange change = new BlockChange(mutation.x() & ChunkSection.MASK, mutation.y(),
                    mutation.z() & ChunkSection.MASK, mutation.stateId());
            grouped.computeIfAbsent(new ChunkKey(chunkX, chunkZ), ignored -> new LinkedHashMap<>())
                    .put(change.packedPosition(), change);
        }
        List<ChunkBlockChanges> changes = new ArrayList<>(grouped.size());
        for (var entry : grouped.entrySet()) {
            Chunk chunk = dimension.getChunkManager().getChunk(entry.getKey().x(), entry.getKey().z());
            changes.add(new ChunkBlockChanges(dimension.getDimensionId().toString(), entry.getKey().x(),
                    entry.getKey().z(), chunk == null ? 0 : chunk.modificationEpoch(),
                    List.copyOf(entry.getValue().values())));
        }
        return List.copyOf(changes);
    }

    private void bumpInventoryRevision(UUID identity) {
        this.inventoryRevisions.merge(identity, 1, Integer::sum);
    }

    private PlayerStateSnapshot snapshot(EntityPlayer player, long tick, long sequence) {
        int flags = 0;
        if (player.isFlying()) flags |= PlayerMovementState.FLYING;
        if (player.isNoClip()) flags |= PlayerMovementState.NO_CLIP;
        if (player.isSprinting()) flags |= PlayerMovementState.SPRINTING;
        if (player.isSneaking()) flags |= PlayerMovementState.SNEAKING;
        Integer vehicleId = player.getVehicle() == null ? null : this.entityNetworkIds.get(player.getVehicle());
        return new PlayerStateSnapshot(tick, sequence, player.getDimensionId().toString(),
                player.x, player.y, player.z, player.motionX, player.motionY, player.motionZ,
                player.yaw, player.pitch, player.onGround, toNetwork(player.getGamemode()), flags,
                player.getHealth(), player.getFoodLevel(), player.getSaturation(), player.getSelectedSlot(),
                vehicleId == null ? 0 : vehicleId, player.getSpectatorFlySpeed());
    }

    private static PlayerGameMode toNetwork(Gamemode mode) {
        return PlayerGameMode.valueOf(mode.name());
    }

    private static Gamemode fromNetwork(PlayerGameMode mode) {
        return Gamemode.valueOf(mode.name());
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        for (SnapshotWork pending : this.snapshots.values()) {
            for (ChunkSnapshotTicket ticket : pending.tickets) ticket.complete(Optional.empty());
        }
        this.snapshots.clear();
        if (this.snapshotWorkers != null) {
            this.snapshotWorkers.shutdownNow();
            try { this.snapshotWorkers.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        for (DimensionManager.DimensionTicket ticket : this.playerTickets.values()) ticket.close();
        this.playerTickets.clear();
        this.players.clear();
        try { this.world.dispose(); }
        finally {
            try { this.lock.release(); } catch (IOException ignored) { }
            try { this.lockChannel.close(); } catch (IOException ignored) { }
        }
    }
}
