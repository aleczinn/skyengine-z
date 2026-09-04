package de.skyengine.server.network;

import de.skyengine.server.ServerConfig;
import de.skyengine.server.ServerCommandDispatcher;
import de.skyengine.server.player.IdentityProvider;
import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.server.player.OfflineIdentityProvider;
import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.Packet;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.shared.network.pack.PackDescriptor;
import de.skyengine.shared.network.pack.RegistryFingerprint;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.network.transport.CompressionTransport;
import de.skyengine.server.world.ChunkReplicationService;
import de.skyengine.server.world.ServerWorldRuntime;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.gameplay.BlockActionRequest;
import de.skyengine.shared.gameplay.EntityActionRequest;
import de.skyengine.shared.gameplay.BlockActionEffectType;
import de.skyengine.server.world.BlockActionOutcome;
import de.skyengine.server.world.EntityActionOutcome;
import de.skyengine.server.world.ContainerOpenData;
import de.skyengine.server.world.ChunkBlockChanges;
import de.skyengine.server.world.WorldSoundEvent;
import de.skyengine.server.world.BlockEntityReplicationUpdate;
import de.skyengine.server.world.InventoryActionOutcome;
import de.skyengine.server.world.EntityReplicationService;
import de.skyengine.server.event.BlockActionEvent;
import de.skyengine.server.event.ChatEvent;
import de.skyengine.server.event.PlayerJoinEvent;
import de.skyengine.server.event.PlayerLeaveEvent;
import de.skyengine.server.event.PlayerLoginEvent;
import de.skyengine.server.event.PlayerKickEvent;
import de.skyengine.server.event.ServerEventBus;
import de.skyengine.server.profile.ServerProfiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** All state transitions and gameplay hand-off occur on the server tick thread. */
public final class ServerSessionManager implements AutoCloseable {
    /** Invisible L0 source ring required by the client's exact neighbour-aware mesher. */
    private static final int CHUNK_MESH_HALO = 1;
    public record NetworkSnapshot(int players, long receivedPackets, long receivedBytes, long sentPackets,
                                  long sentBytes, int inboundQueue, int outboundQueue,
                                  double medianRttMillis, double p95RttMillis, int trackedChunks,
                                  long chunkBatchesEncoded, long chunkPacketsEncoded,
                                  double chunkEncodingMillis, int chunksPending, int snapshotsInFlight,
                                  int chunksReadyToSend, int chunksAwaitingAck, int chunksApplied,
                                  int worldWorkers, int activeWorldWorkers, int queuedWorldTasks) {}
    private static final int MAX_PACKETS_PER_SESSION_PER_TICK = 512;
    private final ServerConfig config;
    private final IdentityProvider identities;
    private final ArrayBlockingQueue<TransportConnection> accepted = new ArrayBlockingQueue<>(1024);
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, PlayerSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, PlayerSession> byIdentity = new LinkedHashMap<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);
    private final byte[] registryFingerprint;
    private final List<RegistryMapping> registryMappings;
    private final List<PackDescriptor> packManifest;
    private final ChunkReplicationService chunks;
    private final EntityReplicationService entities = new EntityReplicationService();
    private final ServerWorldRuntime world;
    private final ServerEventBus events;
    private final ServerProfiler profiler;
    private long serverTick;
    private volatile int onlinePlayers;
    private volatile Consumer<String> lifecycleLogger = ignored -> { };
    private ServerCommandDispatcher commandDispatcher;

    public ServerSessionManager(ServerConfig config) {
        this(config, new OfflineIdentityProvider(), new ServerWorldRuntime() {
            @Override public java.nio.file.Path directory() { return config.serverDirectory(); }
            @Override public void tick(long serverTick) { }
            @Override public void autosave(long serverTick) { }
            @Override public void close() { }
        }, new ServerEventBus(), new ServerProfiler());
    }

    public ServerSessionManager(ServerConfig config, IdentityProvider identities, ServerWorldRuntime world) {
        this(config, identities, world, new ServerEventBus(), new ServerProfiler());
    }

    public ServerSessionManager(ServerConfig config, IdentityProvider identities, ServerWorldRuntime world,
                                ServerEventBus events) {
        this(config, identities, world, events, new ServerProfiler());
    }

    public ServerSessionManager(ServerConfig config, IdentityProvider identities, ServerWorldRuntime world,
                                ServerEventBus events, ServerProfiler profiler) {
        this.config = config;
        this.identities = identities;
        this.world = world;
        this.registryMappings = List.copyOf(world.registryMappings());
        this.packManifest = List.copyOf(world.packManifest());
        this.registryFingerprint = RegistryFingerprint.compute(this.registryMappings);
        this.events = events;
        this.profiler = profiler;
        this.chunks = new ChunkReplicationService(world);
    }

    public synchronized void accept(TransportConnection connection) {
        if (this.closed.get()) {
            connection.disconnect(DisconnectReason.SERVER_STOP, "Server is stopping");
            return;
        }
        int maximumConnections = Math.max(32, this.config.maxPlayers() * 4);
        int current = this.connectionCount.incrementAndGet();
        if (this.closed.get()) {
            this.connectionCount.decrementAndGet();
            connection.disconnect(DisconnectReason.SERVER_STOP, "Server is stopping");
            return;
        }
        if (current > maximumConnections) {
            this.connectionCount.decrementAndGet();
            connection.disconnect(DisconnectReason.LOGIN_FAILED, "Too many pending connections");
            return;
        }
        if (!this.accepted.offer(connection)) {
            this.connectionCount.decrementAndGet();
            connection.disconnect(DisconnectReason.LOGIN_FAILED, "Server connection queue is full");
        }
    }

    public Collection<PlayerSession> sessions() { return List.copyOf(this.sessions.values()); }
    public long serverTick() { return this.serverTick; }
    public void commandDispatcher(ServerCommandDispatcher value) { this.commandDispatcher = value; }
    public void lifecycleLogger(Consumer<String> logger) {
        this.lifecycleLogger = logger == null ? ignored -> { } : logger;
    }

    /** Immutable read-only response safe for the Netty server-list fast path. */
    public CorePackets.ServerStatusResponse statusResponse(CorePackets.ServerStatusRequest request) {
        return new CorePackets.ServerStatusResponse(request.nonce(), EngineInfo.PROTOCOL_VERSION,
                EngineInfo.ENGINE_VERSION, this.config.motd(), this.onlinePlayers, this.config.maxPlayers());
    }

    public NetworkSnapshot networkSnapshot() {
        long rxPackets = 0, rxBytes = 0, txPackets = 0, txBytes = 0;
        int inbound = 0, outbound = 0, tracked = 0, players = 0;
        long encodedBatches = 0, encodedPackets = 0, encodingNanos = 0;
        List<Long> rtts = new ArrayList<>();
        for (PlayerSession session : this.sessions.values()) {
            var stats = session.connection().stats();
            rxPackets += stats.receivedPackets(); rxBytes += stats.receivedBytes();
            txPackets += stats.sentPackets(); txBytes += stats.sentBytes();
            inbound += stats.inboundQueue(); outbound += stats.outboundQueue();
            if (session.connection() instanceof NettyTransportConnection tcp) {
                var encoding = tcp.batchEncodingStats();
                encodedBatches += encoding.batches();
                encodedPackets += encoding.packets();
                encodingNanos += encoding.nanoseconds();
            }
            if (session.state() == ConnectionState.PLAY) {
                players++; tracked += this.chunks.trackedChunks(session);
                if (session.roundTripNanos() > 0) rtts.add(session.roundTripNanos());
            }
        }
        rtts.sort(Long::compareTo);
        double median = rtts.isEmpty() ? 0 : rtts.get((rtts.size() - 1) / 2) / 1_000_000.0;
        double p95 = rtts.isEmpty() ? 0 : rtts.get((int) Math.ceil(rtts.size() * 0.95) - 1) / 1_000_000.0;
        var stream = this.chunks.stats();
        var workers = this.world.workerStats();
        return new NetworkSnapshot(players, rxPackets, rxBytes, txPackets, txBytes, inbound, outbound,
                median, p95, tracked, encodedBatches, encodedPackets, encodingNanos / 1_000_000.0,
                stream.pending(), stream.snapshotInFlight(), stream.readyToSend(), stream.awaitingAck(),
                stream.applied(), workers.workers(), workers.active(), workers.queued());
    }

    public void tick(long tick, long nowNanos) {
        processNetwork(tick, nowNanos);
        replicate();
        flushOutbound();
    }

    /** Decodes and validates input on the tick owner before the authoritative world simulation. */
    public void processNetwork(long tick, long nowNanos) {
        this.serverTick = tick;
        TransportConnection connection;
        while ((connection = this.accepted.poll()) != null) {
            this.sessions.put(connection.id(), new PlayerSession(connection, nowNanos));
        }

        for (PlayerSession session : new ArrayList<>(this.sessions.values())) {
            if (!session.connection().open()) {
                remove(session, DisconnectReason.CLIENT_QUIT);
                continue;
            }
            int handled = 0;
            PacketEnvelope envelope;
            while (handled++ < MAX_PACKETS_PER_SESSION_PER_TICK
                    && (envelope = session.connection().pollInbound()) != null) {
                session.received(nowNanos);
                handle(session, envelope, nowNanos);
                if (!session.connection().open()) break;
            }
            if (session.connection().inboundSize() > MAX_PACKETS_PER_SESSION_PER_TICK * 4) {
                disconnect(session, DisconnectReason.INVALID_PACKET, "Inbound packet flood");
                continue;
            }
            if (session.state() == ConnectionState.PLAY && session.pendingSimulationInputs() > 0) {
                // Consume every intent exactly once. A small bounded catch-up prevents jitter bursts
                // from permanently increasing latency without allowing one client to monopolize a tick.
                int simulations = session.pendingSimulationInputs() > 3 ? 2 : 1;
                while (simulations-- > 0 && session.pendingSimulationInputs() > 0) {
                    simulateMovement(session);
                }
            }
            maintain(session, nowNanos);
        }
        int playing = 0;
        for (PlayerSession session : this.sessions.values()) {
            if (session.state() == ConnectionState.PLAY) playing++;
        }
        this.onlinePlayers = playing;
    }

    /** Publishes world changes produced by the current tick and refreshes spatial interests. */
    public void replicate() {
        this.chunks.tick(this.sessions);
        for (ChunkBlockChanges changes : this.world.drainBlockChanges()) broadcastBlockChanges(changes);
        for (BlockEntityReplicationUpdate update : this.world.drainBlockEntityUpdates()) {
            broadcastBlockEntityUpdate(update);
        }
        for (WorldSoundEvent sound : this.world.drainSoundEvents()) broadcastSound(sound);
        this.entities.apply(this.world.drainEntityUpdates(this.serverTick), this.sessions, this.serverTick);
    }

    public void flushOutbound() {
        for (PlayerSession session : this.sessions.values()) {
            if (session.connection() instanceof NettyTransportConnection tcp) {
                int bytesPerSecond = session.loopbackConnection()
                        ? Math.max(this.config.chunkBytesPerSecond(), 128 * 1024 * 1024)
                        : this.config.chunkBytesPerSecond();
                int perTickBudget = Math.max(64 * 1024, bytesPerSecond / EngineInfo.TICKS_PER_SECOND);
                tcp.flushOutbound(perTickBudget);
            }
        }
    }

    private void handle(PlayerSession session, PacketEnvelope envelope, long nowNanos) {
        Packet packet = envelope.packet();
        try {
            switch (session.state()) {
                case HANDSHAKE -> handleHandshake(session, packet);
                case LOGIN -> handleLogin(session, packet);
                case CONFIGURATION -> handleConfiguration(session, packet);
                case JOINING -> handleJoining(session, packet);
                case PLAY -> handlePlay(session, envelope, nowNanos);
                case DISCONNECTING, CLOSED -> { }
            }
        } catch (IdentityProvider.IdentityException e) {
            disconnect(session, DisconnectReason.LOGIN_FAILED, e.getMessage());
        } catch (RuntimeException e) {
            disconnect(session, DisconnectReason.INVALID_PACKET,
                    e.getMessage() == null ? "Invalid packet" : e.getMessage());
        }
    }

    private void handleHandshake(PlayerSession session, Packet packet) {
        if (packet instanceof CorePackets.ServerStatusRequest request) {
            session.send(statusResponse(request));
            return;
        }
        if (packet instanceof CorePackets.Handshake handshake) {
            if (session.handshakeAccepted()) throw unexpected(packet);
            if (handshake.protocolVersion() != EngineInfo.PROTOCOL_VERSION) {
                disconnect(session, DisconnectReason.PROTOCOL_MISMATCH,
                        "Server protocol " + EngineInfo.PROTOCOL_VERSION + ", client " + handshake.protocolVersion());
                return;
            }
            boolean zstd = this.config.compression().equals("zstd")
                    && session.connection() instanceof CompressionTransport
                    && !session.loopbackConnection();
            session.send(new CorePackets.HandshakeAccepted(EngineInfo.PROTOCOL_VERSION,
                    EngineInfo.ENGINE_VERSION, zstd ? List.of("zstd", "none") : List.of("none")));
            session.handshakeAccepted(true);
            return;
        }
        if (!(packet instanceof CorePackets.CompressionSelect selection) || !session.handshakeAccepted()) {
            throw unexpected(packet);
        }
        boolean zstd = selection.algorithm().equals("zstd") && this.config.compression().equals("zstd")
                && session.connection() instanceof CompressionTransport && !session.loopbackConnection();
        if (!selection.algorithm().equals("none") && !zstd) {
            disconnect(session, DisconnectReason.PROTOCOL_MISMATCH, "Unsupported compression algorithm");
            return;
        }
        String algorithm = zstd ? "zstd" : "none";
        session.send(new CorePackets.CompressionEnabled(algorithm, this.config.compressionThreshold(),
                this.config.maxDecompressedPacketSize(), this.config.compressionLevel()));
        if (zstd) ((CompressionTransport) session.connection()).enableCompression(algorithm,
                this.config.compressionThreshold(), this.config.maxDecompressedPacketSize(),
                this.config.compressionLevel());
        session.connection().transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
    }

    private void handleLogin(PlayerSession session, Packet packet) throws IdentityProvider.IdentityException {
        if (packet instanceof CorePackets.KeepAliveResponse response) {
            handlePong(session, response, System.nanoTime());
            return;
        }
        if (!(packet instanceof CorePackets.LoginStart start)) throw unexpected(packet);
        if (this.byIdentity.size() >= this.config.maxPlayers()) {
            disconnect(session, DisconnectReason.LOGIN_FAILED, "Server is full");
            return;
        }
        PlayerIdentity identity = this.identities.authenticate(start.username(), start.requestedIdentity(),
                session.trustedLocal());
        PlayerLoginEvent loginEvent = this.events.post(new PlayerLoginEvent(identity,
                session.connection().remoteAddress()));
        if (loginEvent.cancelled()) {
            disconnect(session, DisconnectReason.LOGIN_FAILED, loginEvent.cancellationMessage());
            return;
        }
        if (this.byIdentity.containsKey(identity.uuid())) {
            disconnect(session, DisconnectReason.DUPLICATE_LOGIN, "This player is already connected");
            return;
        }
        session.identity(identity);
        this.byIdentity.put(identity.uuid(), session);
        this.lifecycleLogger.accept("Player login accepted: " + identity.name() + " (" + identity.uuid()
                + ") from " + session.connection().remoteAddress());
        session.send(new CorePackets.LoginSuccess(identity.uuid(), identity.name()));
        session.connection().transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
        session.send(new CorePackets.PackManifest(this.packManifest));
        for (RegistryMapping mapping : this.registryMappings) {
            session.send(new CorePackets.RegistryData(mapping));
        }
        // Fingerprint is the configuration-stream terminator. The client acknowledges only after it.
        session.send(new CorePackets.RegistryFingerprint(this.registryFingerprint));
    }

    private void handleConfiguration(PlayerSession session, Packet packet) {
        if (packet instanceof CorePackets.KeepAliveResponse response) {
            handlePong(session, response, System.nanoTime());
        } else if (packet instanceof CorePackets.PackStatus status) {
            if (!status.accepted() || !status.missingRequiredPacks().isEmpty()) {
                disconnect(session, DisconnectReason.PACK_MISMATCH, "Required packs are missing");
            } else {
                session.packAccepted(true);
            }
        } else if (packet instanceof CorePackets.ConfigurationAck ack) {
            if (!session.packAccepted()) throw new IllegalStateException("Pack status must be acknowledged first");
            if (!Arrays.equals(ack.registryFingerprint(), this.registryFingerprint)) {
                disconnect(session, DisconnectReason.PACK_MISMATCH, "Registry fingerprint mismatch");
                return;
            }
            session.entityId(this.nextEntityId.getAndIncrement());
            session.connection().transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
            session.send(new CorePackets.JoinGame(session.identity().uuid(), session.entityId(),
                    EngineInfo.CONTENT_NAMESPACE + ":overworld", this.serverTick, EngineInfo.TICKS_PER_SECOND,
                    this.config.viewDistance(), this.config.simulationDistance()));
        } else {
            throw unexpected(packet);
        }
    }

    private void handleJoining(PlayerSession session, Packet packet) {
        if (packet instanceof CorePackets.KeepAliveResponse response) {
            handlePong(session, response, System.nanoTime());
        } else if (packet instanceof CorePackets.ClientReady) {
            session.connection().transitionState(ConnectionState.JOINING, ConnectionState.PLAY);
            PlayerStateSnapshot initial = this.world.playerJoined(session.identity(), session.entityId(), this.serverTick);
            session.playerState(initial);
            session.sendPlayerState(initial);
            InventoryActionOutcome inventory = this.world.playerInventory(session.identity());
            if (inventory.accepted() || !inventory.content().isEmpty()) {
                session.send(new CorePackets.InventoryContent(inventory.containerId(), inventory.revision(),
                        inventory.content(), inventory.carried()));
                session.sentInventoryRevision(inventory.revision());
            }
            int initialChunkX = floorChunk(initial.x()), initialChunkZ = floorChunk(initial.z());
            session.interestCenterChanged(initial.dimension(), initialChunkX, initialChunkZ);
            this.chunks.updateInterest(session, initial.dimension(), initialChunkX, initialChunkZ,
                    this.config.viewDistance(), CHUNK_MESH_HALO, 0, 0);
            this.entities.updateInterest(session, initial.dimension(), initialChunkX, initialChunkZ,
                    this.config.viewDistance());
            this.events.post(new PlayerJoinEvent(session.identity(), session.entityId()));
            this.lifecycleLogger.accept("Player joined: " + session.identity().name() + " (entity "
                    + session.entityId() + ", " + this.onlinePlayersAfterJoin() + "/"
                    + this.config.maxPlayers() + ")");
            CorePackets.PlayerJoined joined = new CorePackets.PlayerJoined(session.identity().uuid(),
                    session.identity().name(), session.entityId());
            for (PlayerSession other : this.byIdentity.values()) {
                if (other == session || other.state() != ConnectionState.PLAY) continue;
                session.send(new CorePackets.PlayerJoined(other.identity().uuid(), other.identity().name(),
                        other.entityId()));
                other.send(joined);
            }
        } else {
            throw unexpected(packet);
        }
    }

    private void handlePlay(PlayerSession session, PacketEnvelope envelope, long nowNanos) {
        Packet packet = envelope.packet();
        if (packet instanceof CorePackets.KeepAliveResponse response) handlePong(session, response, nowNanos);
        else if (packet instanceof CorePackets.PlayerInput movement) {
            if (envelope.sequence() != movement.input().sequence()) {
                throw new IllegalArgumentException("Movement envelope sequence mismatch");
            }
            handleMovement(session, movement.input(), nowNanos);
        }
        else if (packet instanceof CorePackets.PlayerAbility ability) handleAbility(session, ability, nowNanos);
        else if (packet instanceof CorePackets.SelectedHotbarSlot selection) {
            handleHotbarSelection(session, selection, nowNanos);
        }
        else if (packet instanceof CorePackets.BlockAction action) handleBlockAction(session, action.request(), nowNanos);
        else if (packet instanceof CorePackets.PlayerSwing swing) handleSwing(session, swing, nowNanos);
        else if (packet instanceof CorePackets.EntityAction action) handleEntityAction(session, action.request(), nowNanos);
        else if (packet instanceof CorePackets.InventoryAction action) handleInventory(session, action, nowNanos);
        else if (packet instanceof CorePackets.ContainerClose close) handleContainerClose(session, close);
        else if (packet instanceof CorePackets.ContainerOpenRequest) handleContainerOpenRequest(session, nowNanos);
        else if (packet instanceof CorePackets.RespawnRequest) handleRespawn(session);
        else if (packet instanceof CorePackets.ChunkBatchApplied applied) {
            if (!this.chunks.acknowledge(session, applied.batchId(), applied.leaseId())) {
                throw new IllegalArgumentException("Unknown or duplicate chunk batch acknowledgement");
            }
        }
        else if (packet instanceof CorePackets.ChunkResyncRequest request) {
            if (!session.allowGameplay(nowNanos)) {
                disconnect(session, DisconnectReason.INVALID_PACKET, "Chunk resync rate limit exceeded");
                return;
            }
            this.chunks.requestResync(session, request.dimension(), request.chunkX(), request.chunkZ());
        }
        else if (packet instanceof CorePackets.ChatMessageRequest chat) handleChat(session, chat.message(), nowNanos);
        else if (packet instanceof CorePackets.CommandRequest command) handleCommand(session, command, nowNanos);
        else throw unexpected(packet);
    }

    private void handleSwing(PlayerSession session, CorePackets.PlayerSwing swing, long nowNanos) {
        if (!session.allowGameplay(nowNanos)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Gameplay action rate limit exceeded");
            return;
        }
        this.world.playerSwing(session.identity(), session.entityId());
    }

    private void handleAbility(PlayerSession session, CorePackets.PlayerAbility ability, long nowNanos) {
        if (!session.allowGameplay(nowNanos)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Gameplay action rate limit exceeded");
            return;
        }
        if (ability.actionId() <= session.lastAbilityActionId()
                || ability.inputSequence() <= session.playerState().lastProcessedInputSequence()
                || ability.inputSequence() - session.lastInputSequence() > 4096) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Replayed or late player ability");
            return;
        }
        session.lastAbilityActionId(ability.actionId());
        if (!session.enqueueAbility(ability.actionId(), ability.inputSequence(), ability.action())) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Player ability queue overflow");
        }
    }

    private void handleHotbarSelection(PlayerSession session, CorePackets.SelectedHotbarSlot selection,
                                       long nowNanos) {
        if (!session.allowGameplay(nowNanos)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Gameplay action rate limit exceeded");
            return;
        }
        if (selection.actionId() <= session.lastHotbarActionId()) return;
        session.lastHotbarActionId(selection.actionId());
        PlayerStateSnapshot state = this.world.selectHotbarSlot(session.identity(), session.entityId(),
                session.playerState(), selection.slot(), this.serverTick);
        session.playerState(state);
        session.send(new CorePackets.SelectedHotbarSlotResult(selection.actionId(),
                state.selectedHotbarSlot()));
        session.sendPlayerState(state);
    }

    private void handleMovement(PlayerSession session, PlayerInputFrame input, long nowNanos) {
        if (!session.allowMovement(nowNanos)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Movement rate limit exceeded");
            return;
        }
        if (input.sequence() <= session.lastInputSequence()) return;
        if (input.sequence() - session.lastInputSequence() > 4096) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Movement sequence jump");
            return;
        }
        session.lastInputSequence(input.sequence());
        if (!session.enqueueSimulationInput(input)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Movement input queue overflow");
        }
    }

    private void simulateMovement(PlayerSession session) {
        this.profiler.begin(ServerProfiler.Phase.PLAYER_SIMULATION);
        try {
        PlayerInputFrame input = session.pollSimulationInput();
        if (input == null) return;
        PlayerStateSnapshot base = session.playerState();
        for (PlayerSession.PendingAbility ability : session.abilitiesThrough(input.sequence())) {
            base = this.world.applyPlayerAbility(session.identity(), session.entityId(), base,
                    ability.action(), this.serverTick);
        }
        String movementDimension = base.dimension();
        PlayerStateSnapshot next = this.world.applyPlayerInput(session.identity(), session.entityId(),
                base, input, this.serverTick, (chunkX, chunkZ) ->
                        this.chunks.isApplied(session, movementDimension, chunkX, chunkZ));
        if (next.lastProcessedInputSequence() != input.sequence() || next.serverTick() != this.serverTick) {
            throw new IllegalStateException("World returned inconsistent authoritative player state");
        }
        if (!next.dimension().equals(session.playerState().dimension()) && session.activeContainerId() != 0) {
            session.send(new CorePackets.ContainerClosed(session.activeContainerId()));
            this.world.closeContainer(session.identity(), session.activeContainerId());
            session.activeContainerId(0);
        }
        session.playerState(next);
        session.sendPlayerState(next);
        syncInventoryIfDirty(session);
        int chunkX = floorChunk(next.x()), chunkZ = floorChunk(next.z());
        double streamYaw = Math.toRadians(input.yaw());
        float streamX = (float) (input.forward() * Math.sin(streamYaw) + input.strafe() * Math.cos(streamYaw));
        float streamZ = (float) (-input.forward() * Math.cos(streamYaw) + input.strafe() * Math.sin(streamYaw));
        if (streamX * streamX + streamZ * streamZ < 1.0E-4F) {
            streamX = (float) next.velocityX();
            streamZ = (float) next.velocityZ();
        }
        if (session.interestCenterChanged(next.dimension(), chunkX, chunkZ)) {
            this.chunks.updateInterest(session, next.dimension(), chunkX, chunkZ,
                    this.config.viewDistance(), CHUNK_MESH_HALO, streamX, streamZ);
            this.entities.updateInterest(session, next.dimension(), chunkX, chunkZ,
                    this.config.viewDistance());
        } else {
            this.chunks.reprioritize(session, next.dimension(), chunkX, chunkZ,
                    streamX, streamZ);
        }
        } finally {
            this.profiler.end(ServerProfiler.Phase.PLAYER_SIMULATION);
        }
    }

    /** Tick-thread-only authoritative game-mode change used by commands and development controls. */
    public boolean setGameMode(PlayerSession session, PlayerGameMode mode) {
        if (session == null || mode == null || session.state() != ConnectionState.PLAY
                || session.playerState() == null || session.identity() == null) return false;
        PlayerStateSnapshot next = this.world.changePlayerGameMode(session.identity(), session.entityId(),
                session.playerState(), mode, this.serverTick);
        session.playerState(next);
        session.sendPlayerState(next);
        this.lifecycleLogger.accept("Player game mode: " + session.identity().name() + " -> " + mode);
        return true;
    }

    private void handleBlockAction(PlayerSession session, BlockActionRequest request, long nowNanos) {
        if (!session.allowGameplay(nowNanos)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Gameplay action rate limit exceeded");
            return;
        }
        if (request.actionId() <= session.lastBlockActionId()) return;
        session.lastBlockActionId(request.actionId());
        PlayerStateSnapshot player = session.playerState();
        BlockActionEvent event = this.events.post(new BlockActionEvent(session.identity(), request));
        if (event.cancelled()) {
            session.send(new CorePackets.BlockActionResult(request.actionId(), false, event.cancellationMessage()));
            return;
        }
        if (!request.dimension().equals(player.dimension())) {
            session.send(new CorePackets.BlockActionResult(request.actionId(), false, "Wrong dimension"));
            return;
        }
        double dx = request.x() + 0.5 - player.x();
        double dy = request.y() + 0.5 - (player.y() + 1.62);
        double dz = request.z() + 0.5 - player.z();
        if (dx * dx + dy * dy + dz * dz > 36) {
            session.send(new CorePackets.BlockActionResult(request.actionId(), false, "Out of reach"));
            return;
        }
        BlockActionOutcome outcome = this.world.handleBlockAction(session.identity(), player, request, this.serverTick);
        session.send(new CorePackets.BlockActionResult(outcome.actionId(), outcome.accepted(), outcome.message(),
                outcome.corrections()));
        if (!outcome.accepted()) return;
        CorePackets.BlockActionEffect effect = createBlockActionEffect(session, request, outcome);
        if (effect != null) broadcastBlockActionEffect(effect);
        for (var changes : outcome.chunkChanges()) broadcastBlockChanges(changes);
        if (outcome.inventoryChanged()) {
            InventoryActionOutcome inventory = this.world.playerInventory(session.identity());
            session.send(new CorePackets.InventoryContent(inventory.containerId(), inventory.revision(),
                    inventory.content(), inventory.carried()));
            session.sentInventoryRevision(inventory.revision());
        }
        if (outcome.openedContainer() != null) {
            sendOpenedContainer(session, outcome.openedContainer());
        }
    }

    private CorePackets.BlockActionEffect createBlockActionEffect(PlayerSession session,
                                                                   BlockActionRequest request,
                                                                   BlockActionOutcome outcome) {
        BlockActionEffectType type;
        int stateId = request.expectedStateId();
        int x = request.x(), y = request.y(), z = request.z();
        if (outcome.chunkChanges().isEmpty()) {
            if (request.action() != BlockActionRequest.Action.START_BREAK) return null;
            type = BlockActionEffectType.HIT;
        } else if (request.action() == BlockActionRequest.Action.START_BREAK
                || request.action() == BlockActionRequest.Action.FINISH_BREAK) {
            type = BlockActionEffectType.BREAK;
        } else {
            type = BlockActionEffectType.PLACE;
            outer: for (ChunkBlockChanges changes : outcome.chunkChanges()) {
                for (var change : changes.changes()) {
                    if (change.stateId() == 0) continue;
                    stateId = change.stateId();
                    x = (changes.chunkX() << 5) + change.localX();
                    y = change.y();
                    z = (changes.chunkZ() << 5) + change.localZ();
                    break outer;
                }
            }
        }
        return new CorePackets.BlockActionEffect(request.actionId(), session.entityId(), type,
                request.dimension(), stateId, x, y, z, request.face(),
                request.hitX(), request.hitY(), request.hitZ());
    }

    private void broadcastBlockActionEffect(CorePackets.BlockActionEffect effect) {
        int chunkX = effect.x() >> 5, chunkZ = effect.z() >> 5;
        for (PlayerSession target : this.byIdentity.values()) {
            if (target.state() == ConnectionState.PLAY && this.chunks.tracks(target,
                    effect.dimension(), chunkX, chunkZ)) target.send(effect);
        }
    }

    private void broadcastBlockChanges(ChunkBlockChanges changes) {
        Packet update = changes.changes().size() == 1
                ? new CorePackets.BlockUpdate(changes.dimension(), changes.chunkX(), changes.chunkZ(),
                        changes.revision(), changes.changes().getFirst())
                : new CorePackets.MultiBlockUpdate(changes.dimension(), changes.chunkX(), changes.chunkZ(),
                        changes.revision(), changes.changes());
        for (PlayerSession target : this.byIdentity.values()) {
            if (target.state() == ConnectionState.PLAY && this.chunks.tracks(target,
                    changes.dimension(), changes.chunkX(), changes.chunkZ())) target.send(update);
        }
    }

    private void broadcastSound(WorldSoundEvent sound) {
        int chunkX = (int) Math.floor(sound.x()) >> 5;
        int chunkZ = (int) Math.floor(sound.z()) >> 5;
        CorePackets.WorldSound packet = new CorePackets.WorldSound(sound.dimension(), sound.type(),
                sound.data(), sound.x(), sound.y(), sound.z());
        for (PlayerSession target : this.byIdentity.values()) {
            if (target.state() == ConnectionState.PLAY && this.chunks.tracks(target,
                    sound.dimension(), chunkX, chunkZ)) target.send(packet);
        }
    }

    private void broadcastBlockEntityUpdate(BlockEntityReplicationUpdate update) {
        CorePackets.BlockEntityUpdate packet = new CorePackets.BlockEntityUpdate(update.dimension(),
                update.chunkX(), update.chunkZ(), update.blockEntity());
        for (PlayerSession target : this.byIdentity.values()) {
            if (target.state() == ConnectionState.PLAY && this.chunks.tracks(target,
                    update.dimension(), update.chunkX(), update.chunkZ())) target.send(packet);
        }
    }

    private void handleInventory(PlayerSession session, CorePackets.InventoryAction action, long nowNanos) {
        if (!session.allowInventory(nowNanos)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Inventory action rate limit exceeded");
            return;
        }
        InventoryActionOutcome outcome = this.world.handleInventoryAction(session.identity(), action.request(),
                this.serverTick);
        session.send(new CorePackets.InventoryTransactionResult(outcome.transactionId(), outcome.accepted(),
                outcome.message()));
        // Always return an authoritative correction when a container snapshot is available.
        if (!outcome.content().isEmpty() || outcome.accepted()) {
            session.send(new CorePackets.InventoryContent(outcome.containerId(), outcome.revision(),
                    outcome.content(), outcome.carried()));
            session.sentInventoryRevision(outcome.revision());
        }
    }

    private void handleEntityAction(PlayerSession session, EntityActionRequest request, long nowNanos) {
        if (!session.allowGameplay(nowNanos)) {
            disconnect(session, DisconnectReason.INVALID_PACKET, "Gameplay action rate limit exceeded");
            return;
        }
        EntityActionOutcome outcome = this.world.handleEntityAction(session.identity(), request, this.serverTick);
        session.send(new CorePackets.EntityActionResult(outcome.actionId(), outcome.accepted(), outcome.message()));
        if (outcome.inventoryChanged()) syncInventoryIfDirty(session);
    }

    private void syncInventoryIfDirty(PlayerSession session) {
        int containerId = session.activeContainerId();
        int revision = this.world.containerInventoryRevision(session.identity(), containerId);
        if (revision != session.sentInventoryRevision()) {
            InventoryActionOutcome inventory = this.world.containerInventory(session.identity(), containerId);
            session.send(new CorePackets.InventoryContent(inventory.containerId(), inventory.revision(),
                    inventory.content(), inventory.carried()));
            session.sentInventoryRevision(inventory.revision());
        }
        if (containerId != 0) {
            int[] data = this.world.containerData(session.identity(), containerId);
            int hash = Arrays.hashCode(data);
            if (hash != session.sentContainerDataHash()) {
                session.send(new CorePackets.ContainerData(containerId, data));
                session.sentContainerDataHash(hash);
            }
        }
    }

    private void handleContainerClose(PlayerSession session, CorePackets.ContainerClose close) {
        if (session.activeContainerId() != close.containerId()) return;
        this.world.closeContainer(session.identity(), close.containerId());
        session.activeContainerId(0);
        syncInventoryIfDirty(session);
    }

    private void handleContainerOpenRequest(PlayerSession session, long nowNanos) {
        if (!session.allowInventory(nowNanos)) return;
        ContainerOpenData opened = this.world.openPlayerInventory(session.identity());
        if (opened != null) sendOpenedContainer(session, opened);
    }

    private void handleRespawn(PlayerSession session) {
        PlayerStateSnapshot previous = session.playerState();
        if (previous == null || previous.health() > 0) return;
        if (session.activeContainerId() != 0) {
            session.send(new CorePackets.ContainerClosed(session.activeContainerId()));
            this.world.closeContainer(session.identity(), session.activeContainerId());
            session.activeContainerId(0);
        }
        PlayerStateSnapshot next = this.world.respawnPlayer(session.identity(), session.entityId(),
                previous, this.serverTick);
        session.playerState(next);
        session.sendPlayerState(next);
        int chunkX = floorChunk(next.x()), chunkZ = floorChunk(next.z());
        if (session.interestCenterChanged(next.dimension(), chunkX, chunkZ)) {
            this.chunks.updateInterest(session, next.dimension(), chunkX, chunkZ,
                    this.config.viewDistance(), CHUNK_MESH_HALO, 0, 0);
            this.entities.updateInterest(session, next.dimension(), chunkX, chunkZ,
                    this.config.viewDistance());
        }
        syncInventoryIfDirty(session);
    }

    private void sendOpenedContainer(PlayerSession session, ContainerOpenData opened) {
        session.activeContainerId(opened.containerId());
        session.send(new CorePackets.ContainerOpen(opened.containerId(), opened.kind(),
                opened.containerSlots(), opened.rows(), opened.dimension(),
                opened.x(), opened.y(), opened.z()));
        InventoryActionOutcome inventory = this.world.containerInventory(
                session.identity(), opened.containerId());
        session.send(new CorePackets.InventoryContent(inventory.containerId(), inventory.revision(),
                inventory.content(), inventory.carried()));
        session.sentInventoryRevision(inventory.revision());
        int[] data = this.world.containerData(session.identity(), opened.containerId());
        session.send(new CorePackets.ContainerData(opened.containerId(), data));
        session.sentContainerDataHash(Arrays.hashCode(data));
    }

    private void handleChat(PlayerSession session, String message, long nowNanos) {
        if (!session.allowChat(nowNanos)) return;
        String normalized = message.strip();
        if (normalized.isEmpty()) return;
        if (normalized.startsWith("/")) {
            session.send(new CorePackets.CommandResult(0, false,
                    List.of("Commands must use the command request channel")));
            return;
        }
        ChatEvent event = this.events.post(new ChatEvent(session.identity(), normalized));
        if (event.cancelled()) return;
        CorePackets.ChatMessage broadcast = new CorePackets.ChatMessage(session.identity().uuid(),
                session.identity().name(), this.serverTick, event.message());
        for (PlayerSession target : this.byIdentity.values()) {
            if (target.state() == ConnectionState.PLAY) target.send(broadcast);
        }
    }

    private void handleCommand(PlayerSession session, CorePackets.CommandRequest request, long nowNanos) {
        if (!session.allowChat(nowNanos)) return;
        ServerCommandDispatcher.Result result = this.commandDispatcher == null
                ? new ServerCommandDispatcher.Result(false, List.of("Commands are unavailable"))
                : this.commandDispatcher.execute(request.command(), session);
        session.send(new CorePackets.CommandResult(request.commandId(), result.success(), result.messages()));
    }

    private void maintain(PlayerSession session, long nowNanos) {
        long timeout = this.config.timeoutSeconds() * 1_000_000_000L;
        if (nowNanos - session.lastPacketReceivedNanos() >= timeout) {
            disconnect(session, DisconnectReason.TIMEOUT, "Connection timed out");
            return;
        }
        long interval = this.config.keepAliveIntervalSeconds() * 1_000_000_000L;
        if (session.state() != ConnectionState.HANDSHAKE
                && nowNanos - session.lastPingSentNanos() >= interval) {
            long nonce = nowNanos ^ session.connection().id().hashCode();
            session.send(new CorePackets.KeepAlive(nonce, nowNanos));
            session.pingSent(nowNanos, nonce);
        }
    }

    private void handlePong(PlayerSession session, CorePackets.KeepAliveResponse response, long nowNanos) {
        if (session.outstandingPingNonce() == 0 || response.nonce() != session.outstandingPingNonce()) {
            throw new IllegalArgumentException("Invalid keepalive nonce");
        }
        session.pong(nowNanos);
    }

    public void disconnect(PlayerSession session, DisconnectReason reason, String message) {
        if (reason == DisconnectReason.KICKED && session.identity() != null) {
            this.events.post(new PlayerKickEvent(session.identity(), message));
            this.lifecycleLogger.accept("Player kicked: " + session.identity().name() + " - " + message);
        }
        session.connection().disconnect(reason, message);
        remove(session, reason);
    }

    private void remove(PlayerSession session, DisconnectReason reason) {
        if (this.sessions.remove(session.connection().id()) == null) return;
        this.connectionCount.decrementAndGet();
        this.chunks.remove(session);
        this.entities.removeSession(session);
        PlayerIdentity identity = session.identity();
        if (identity != null) {
            boolean hadJoined = session.playerState() != null;
            if (session.playerState() != null) {
                this.world.playerLeft(identity, session.entityId(), session.playerState());
            }
            this.byIdentity.remove(identity.uuid(), session);
            this.events.post(new PlayerLeaveEvent(identity, session.entityId(), reason));
            this.lifecycleLogger.accept((hadJoined ? "Player left: " : "Player login closed: ")
                    + identity.name() + " (" + reason + ")");
            CorePackets.PlayerLeft left = new CorePackets.PlayerLeft(identity.uuid(), reason);
            for (PlayerSession other : this.byIdentity.values()) {
                if (other.state() != ConnectionState.PLAY) continue;
                other.send(left);
                if (hadJoined && session.entityId() > 0) {
                    // Immediate lifecycle cleanup; the world/index despawn arriving during
                    // replication is intentionally idempotent on clients.
                    other.send(new CorePackets.EntityDespawn(session.entityId(), 0));
                }
            }
        }
        session.connection().close();
    }

    @Override public synchronized void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        for (PlayerSession session : new ArrayList<>(this.sessions.values())) {
            disconnect(session, DisconnectReason.SERVER_STOP, "Server stopped");
        }
        TransportConnection pending;
        while ((pending = this.accepted.poll()) != null) {
            this.connectionCount.decrementAndGet();
            pending.disconnect(DisconnectReason.SERVER_STOP, "Server stopped");
            pending.close();
        }
    }

    private static IllegalArgumentException unexpected(Packet packet) {
        return new IllegalArgumentException("Unexpected packet " + packet.getClass().getSimpleName());
    }

    private static int floorChunk(double blockCoordinate) {
        return Math.floorDiv((int) Math.floor(blockCoordinate), 32);
    }

    private int onlinePlayersAfterJoin() {
        int players = 0;
        for (PlayerSession candidate : this.sessions.values()) {
            if (candidate.state() == ConnectionState.PLAY) players++;
        }
        return players;
    }
}
