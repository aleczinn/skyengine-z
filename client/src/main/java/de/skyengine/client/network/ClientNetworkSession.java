package de.skyengine.client.network;

import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.Packet;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.pack.PackDescriptor;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.network.transport.CompressionTransport;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.gameplay.BlockActionRequest;
import de.skyengine.shared.gameplay.InventoryActionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Client-owner-thread handshake/configuration/play controller shared by local and TCP sessions. */
public final class ClientNetworkSession {
    @FunctionalInterface
    public interface PackValidator {
        PackValidation validate(List<PackDescriptor> packs);
    }

    public record PackValidation(boolean accepted, List<String> missingRequiredPacks) {
        public PackValidation {
            missingRequiredPacks = List.copyOf(missingRequiredPacks);
            if (accepted && !missingRequiredPacks.isEmpty()) {
                throw new IllegalArgumentException("Accepted pack configuration cannot miss required packs");
            }
        }
        public static PackValidation acceptAll() { return new PackValidation(true, List.of()); }
    }

    public interface Listener {
        default void joined(CorePackets.JoinGame packet) {}
        default void playerJoined(CorePackets.PlayerJoined packet) {}
        default void playerLeft(CorePackets.PlayerLeft packet) {}
        default void disconnected(DisconnectReason reason, String message) {}
        default void registryReceived(RegistryMapping mapping) {}
        default void authoritativePlayerState(PlayerStateSnapshot state) {}
        default void selectedHotbarSlotResult(CorePackets.SelectedHotbarSlotResult result) {}
        default void blockActionResult(CorePackets.BlockActionResult result) {}
        default void blockActionEffect(CorePackets.BlockActionEffect effect) {}
        default void entityActionResult(CorePackets.EntityActionResult result) {}
        default void inventoryTransactionResult(CorePackets.InventoryTransactionResult result) {}
        default void containerOpened(CorePackets.ContainerOpen opened) {}
        default void containerClosed(CorePackets.ContainerClosed closed) {}
        default void worldSound(CorePackets.WorldSound sound) {}
        default void entityEvent(CorePackets.EntityEvent event) {}
        default void chatMessage(CorePackets.ChatMessage message) {}
        default void commandResult(CorePackets.CommandResult result) {}
    }

    private final TransportConnection connection;
    private final ReplicatedChunkCache chunks;
    private final PackValidator packValidator;
    private final Listener listener;
    private final ReplicatedInventory inventory = new ReplicatedInventory();
    private final ReplicatedEntityCache entities = new ReplicatedEntityCache();
    private final Map<String, RegistryMapping> registries = new HashMap<>();
    private String username;
    private UUID requestedIdentity;
    private byte[] registryFingerprint;
    private boolean packManifestReceived;
    private boolean packAccepted;
    private boolean started;
    private boolean configurationAcknowledged;
    private boolean compressionSelectionSent;

    public ClientNetworkSession(TransportConnection connection, ReplicatedChunkCache chunks,
                                PackValidator packValidator, Listener listener) {
        this.connection = Objects.requireNonNull(connection);
        this.chunks = Objects.requireNonNull(chunks);
        this.packValidator = Objects.requireNonNull(packValidator);
        this.listener = listener == null ? new Listener() {} : listener;
    }

    public void start(String username, UUID requestedIdentity) {
        if (this.started) throw new IllegalStateException("Client session already started");
        this.started = true;
        this.username = Objects.requireNonNull(username);
        this.requestedIdentity = requestedIdentity;
        send(new CorePackets.Handshake(EngineInfo.PROTOCOL_VERSION, EngineInfo.ENGINE_VERSION));
        flush();
    }

    /** Called from the client update thread. Returns the number of processed packets. */
    public int update() {
        int processed = 0;
        try {
            acknowledgePreparedChunkBatches();
        } catch (ProtocolException failure) {
            rejectServerPacket(failure);
            flush();
            return processed;
        }
        PacketEnvelope envelope;
        while ((envelope = this.connection.pollInbound()) != null) {
            processed++;
            try { handle(envelope.packet()); }
            catch (ProtocolException | RuntimeException e) {
                rejectServerPacket(e);
                break;
            }
        }
        try { acknowledgePreparedChunkBatches(); }
        catch (ProtocolException failure) { rejectServerPacket(failure); }
        flush();
        return processed;
    }

    private void acknowledgePreparedChunkBatches() throws ProtocolException {
        for (ReplicatedChunkCache.AppliedBatch batch : this.chunks.drainCompletedBatchIds()) {
            sendPlay(new CorePackets.ChunkBatchApplied(batch.batchId(), batch.leaseId()));
        }
    }

    private void rejectServerPacket(Exception failure) {
        String message = failure.getMessage() == null ? "Invalid server packet" : failure.getMessage();
        this.connection.disconnect(DisconnectReason.INVALID_PACKET, message);
        this.listener.disconnected(DisconnectReason.INVALID_PACKET, failure.getMessage());
    }

    public ConnectionState state() { return this.connection.state(); }
    public Map<String, RegistryMapping> registries() { return Map.copyOf(this.registries); }
    public ReplicatedChunkCache chunks() { return this.chunks; }
    public ReplicatedInventory inventory() { return this.inventory; }
    public ReplicatedEntityCache entities() { return this.entities; }

    public void sendInput(PlayerInputFrame input) {
        if (state() != ConnectionState.PLAY) throw new IllegalStateException("Player input outside PLAY");
        if (!this.connection.send(new PacketEnvelope(new CorePackets.PlayerInput(input), input.sequence()))) {
            throw new IllegalStateException("Client movement queue is full");
        }
    }

    public void sendAbility(long actionId, long inputSequence,
                            de.skyengine.shared.gameplay.PlayerAbilityAction action) {
        sendPlay(new CorePackets.PlayerAbility(actionId, inputSequence, action));
    }
    public void selectHotbarSlot(long actionId, int slot) {
        sendPlay(new CorePackets.SelectedHotbarSlot(actionId, slot));
    }

    public void sendBlockAction(BlockActionRequest request) { sendPlay(new CorePackets.BlockAction(request)); }
    public void sendSwing(long actionId) { sendPlay(new CorePackets.PlayerSwing(actionId)); }
    public void sendEntityAction(de.skyengine.shared.gameplay.EntityActionRequest request) {
        sendPlay(new CorePackets.EntityAction(request));
    }
    public void sendInventoryAction(InventoryActionRequest request) { sendPlay(new CorePackets.InventoryAction(request)); }
    public void closeContainer(int containerId) {
        sendPlay(new CorePackets.ContainerClose(containerId));
        this.inventory.remove(containerId);
    }
    public void requestPlayerInventory() { sendPlay(new CorePackets.ContainerOpenRequest()); }
    public void requestRespawn() { sendPlay(new CorePackets.RespawnRequest()); }
    public void sendChat(String message) { sendPlay(new CorePackets.ChatMessageRequest(message)); }
    public void sendCommand(long commandId, String command) {
        sendPlay(new CorePackets.CommandRequest(commandId, command));
    }
    public void requestChunkResync(ReplicatedChunkCache.ResyncRequest request) {
        sendPlay(new CorePackets.ChunkResyncRequest(request.dimension(), request.chunkX(), request.chunkZ(),
                request.knownRevision()));
    }

    private void handle(Packet packet) throws ProtocolException {
        if (packet instanceof CorePackets.KeepAlive keepAlive) {
            send(new CorePackets.KeepAliveResponse(keepAlive.nonce()));
            return;
        }
        if (packet instanceof CorePackets.Disconnect disconnect) {
            this.listener.disconnected(disconnect.reason(), disconnect.message());
            this.connection.close();
            return;
        }
        switch (this.connection.state()) {
            case HANDSHAKE -> {
                if (packet instanceof CorePackets.HandshakeAccepted accepted) {
                    if (this.compressionSelectionSent) throw unexpected(packet);
                    if (accepted.protocolVersion() != EngineInfo.PROTOCOL_VERSION) {
                        throw new ProtocolException("Server changed protocol during handshake");
                    }
                    String algorithm = accepted.compressionAlgorithms().contains("zstd")
                            && this.connection instanceof CompressionTransport ? "zstd" : "none";
                    send(new CorePackets.CompressionSelect(algorithm));
                    this.compressionSelectionSent = true;
                } else if (packet instanceof CorePackets.CompressionEnabled enabled) {
                    if (!this.compressionSelectionSent) throw unexpected(packet);
                    if (enabled.algorithm().equals("zstd")) {
                        if (!(this.connection instanceof CompressionTransport transport)) {
                            throw new ProtocolException("Server enabled unavailable Zstd transport");
                        }
                        transport.enableCompression(enabled.algorithm(), enabled.threshold(),
                                enabled.maximumDecompressedBytes(), enabled.level());
                    }
                    this.connection.transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
                    send(new CorePackets.LoginStart(this.username, this.requestedIdentity));
                }
                else throw unexpected(packet);
            }
            case LOGIN -> {
                if (packet instanceof CorePackets.LoginFailure failure) {
                    this.listener.disconnected(failure.reason(), failure.message());
                    this.connection.close();
                } else if (packet instanceof CorePackets.LoginSuccess) {
                    this.connection.transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
                } else throw unexpected(packet);
            }
            case CONFIGURATION -> handleConfiguration(packet);
            case JOINING -> {
                if (!(packet instanceof CorePackets.JoinGame join)) throw unexpected(packet);
                this.listener.joined(join);
                send(new CorePackets.ClientReady(this.chunks.lastCompletedBatch()));
                this.connection.transitionState(ConnectionState.JOINING, ConnectionState.PLAY);
            }
            case PLAY -> handlePlay(packet);
            case DISCONNECTING, CLOSED -> { }
        }
    }

    private void handleConfiguration(Packet packet) throws ProtocolException {
        if (packet instanceof CorePackets.PackManifest manifest) {
            PackValidation validation = Objects.requireNonNull(this.packValidator.validate(manifest.packs()));
            send(new CorePackets.PackStatus(validation.accepted(), validation.missingRequiredPacks()));
            this.packManifestReceived = true;
            this.packAccepted = validation.accepted();
        } else if (packet instanceof CorePackets.RegistryFingerprint fingerprint) {
            this.registryFingerprint = fingerprint.sha256();
        } else if (packet instanceof CorePackets.RegistryData data) {
            this.registries.put(data.mapping().registry(), data.mapping());
            this.listener.registryReceived(data.mapping());
        } else throw unexpected(packet);
        acknowledgeConfigurationWhenReady();
    }

    private void acknowledgeConfigurationWhenReady() {
        if (this.configurationAcknowledged || !this.packManifestReceived || !this.packAccepted
                || this.registryFingerprint == null) return;
        send(new CorePackets.ConfigurationAck(this.registryFingerprint));
        this.configurationAcknowledged = true;
        this.connection.transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
    }

    private void handlePlay(Packet packet) throws ProtocolException {
        if (packet instanceof CorePackets.PlayerJoined joined) this.listener.playerJoined(joined);
        else if (packet instanceof CorePackets.PlayerLeft left) this.listener.playerLeft(left);
        else if (packet instanceof CorePackets.PlayerState state) this.listener.authoritativePlayerState(state.state());
        else if (packet instanceof CorePackets.SelectedHotbarSlotResult result) {
            this.listener.selectedHotbarSlotResult(result);
        }
        else if (packet instanceof CorePackets.BlockActionResult result) this.listener.blockActionResult(result);
        else if (packet instanceof CorePackets.BlockActionEffect effect) this.listener.blockActionEffect(effect);
        else if (packet instanceof CorePackets.EntityActionResult result) this.listener.entityActionResult(result);
        else if (packet instanceof CorePackets.InventoryTransactionResult result) {
            this.listener.inventoryTransactionResult(result);
        }
        else if (packet instanceof CorePackets.InventoryContent content) this.inventory.accept(content);
        else if (packet instanceof CorePackets.InventorySlotUpdate slot) this.inventory.accept(slot);
        else if (packet instanceof CorePackets.ContainerOpen opened) this.listener.containerOpened(opened);
        else if (packet instanceof CorePackets.ContainerData data) this.inventory.accept(data);
        else if (packet instanceof CorePackets.ContainerClosed closed) {
            this.inventory.remove(closed.containerId());
            this.listener.containerClosed(closed);
        }
        else if (packet instanceof CorePackets.WorldSound sound) this.listener.worldSound(sound);
        else if (packet instanceof CorePackets.ChatMessage chat) this.listener.chatMessage(chat);
        else if (packet instanceof CorePackets.CommandResult result) this.listener.commandResult(result);
        else if (packet instanceof CorePackets.EntitySpawn spawn) this.entities.spawn(spawn);
        else if (packet instanceof CorePackets.EntityState state) this.entities.state(state);
        else if (packet instanceof CorePackets.EntityMetadata metadata) this.entities.metadata(metadata);
        else if (packet instanceof CorePackets.EntityDespawn despawn) this.entities.despawn(despawn);
        else if (packet instanceof CorePackets.EntityEvent event) this.listener.entityEvent(event);
        else if (packet instanceof CorePackets.ChunkBatchStart
                || packet instanceof CorePackets.ChunkColumnData
                || packet instanceof CorePackets.ChunkColumnFragment
                || packet instanceof CorePackets.ChunkBatchEnd
                || packet instanceof CorePackets.UnloadChunk
                || packet instanceof CorePackets.BlockUpdate
                || packet instanceof CorePackets.MultiBlockUpdate
                || packet instanceof CorePackets.BlockEntityUpdate) {
            this.chunks.accept(packet);
        }
        else throw unexpected(packet);
    }

    private void send(Packet packet) {
        if (!this.connection.send(new PacketEnvelope(packet))) {
            throw new IllegalStateException("Client outbound queue is full");
        }
    }

    private void sendPlay(Packet packet) {
        if (state() != ConnectionState.PLAY) throw new IllegalStateException("Gameplay packet outside PLAY");
        send(packet);
    }

    private void flush() {
        if (this.connection instanceof de.skyengine.server.network.NettyTransportConnection tcp) {
            tcp.flushOutbound(256 * 1024);
        }
    }

    private static ProtocolException unexpected(Packet packet) {
        return new ProtocolException("Unexpected server packet " + packet.getClass().getSimpleName());
    }
}
