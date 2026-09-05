package de.skyengine.client.network;

import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.ProtocolLimits;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.network.transport.TransportConnection;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-owner-thread lifecycle around an asynchronous TCP connect and {@link ClientNetworkSession}.
 * DNS and socket setup happen on a virtual thread; packet handling remains in {@link #update()}.
 */
public class ClientMultiplayerConnection implements AutoCloseable {
    public enum Phase {
        IDLE, CONNECTING, HANDSHAKE, LOGIN, CONFIGURATION, JOINING, PLAY, DISCONNECTED, FAILED
    }

    private record Connected(NettyClientTransport transport,
                             de.skyengine.server.network.NettyTransportConnection connection,
                             Throwable failure) {}

    private final AtomicReference<Connected> completedConnect = new AtomicReference<>();
    /** Ephemeral offline identity: stable for this process, fresh for every client launch. */
    private final UUID launchIdentity = UUID.randomUUID();
    private volatile Phase phase = Phase.IDLE;
    private volatile String detail = "";
    private volatile boolean cancelled;
    private volatile NettyClientTransport connectingTransport;
    private Thread connectThread;
    private NettyClientTransport transport;
    private TransportConnection connection;
    private ClientNetworkSession session;
    private ReplicatedChunkCache chunks;
    private ReplicatedChunkCache.Listener chunkListener;
    private CorePackets.JoinGame joinGame;
    private PlayerStateSnapshot playerState;
    private DisconnectReason disconnectReason;
    private IntUnaryOperator blockStateLocalToNetwork = id -> id;
    private IntUnaryOperator blockStateNetworkToLocal = id -> id;
    private final ConcurrentLinkedQueue<String> receivedMessages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CorePackets.ContainerOpen> openedContainers = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> closedContainers = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CorePackets.WorldSound> worldSounds = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CorePackets.EntityEvent> entityEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CorePackets.BlockActionResult> blockActionResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CorePackets.BlockActionEffect> blockActionEffects = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CorePackets.InventoryTransactionResult> inventoryResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CorePackets.SelectedHotbarSlotResult> hotbarResults =
            new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> itemNetworkIds = new HashMap<>();
    private final Map<Integer, CorePackets.PlayerJoined> remotePlayers = new HashMap<>();

    public void connect(ServerAddress address, String username) {
        Objects.requireNonNull(address);
        if (this.phase != Phase.IDLE && this.phase != Phase.DISCONNECTED && this.phase != Phase.FAILED) {
            throw new IllegalStateException("A multiplayer connection is already active");
        }
        closeResources(false);
        resetForConnect();
        this.phase = Phase.CONNECTING;
        this.detail = address.display();

        String safeUsername = normalizeUsername(username);
        // Written before the virtual thread is started and consumed later by the owner thread.
        this.pendingUsername = safeUsername;
        this.connectThread = Thread.ofVirtual().name("Server Connect").start(() -> {
            NettyClientTransport created = new NettyClientTransport(CoreProtocol.createRegistry(),
                    ProtocolLimits.MAX_FRAME_BYTES);
            this.connectingTransport = created;
            try {
                var connection = created.connect(address.socketAddress());
                if (this.cancelled) {
                    created.close();
                    return;
                }
                this.completedConnect.compareAndSet(null, new Connected(created, connection, null));
            } catch (Throwable failure) {
                try { created.close(); } catch (RuntimeException ignored) { }
                this.completedConnect.compareAndSet(null, new Connected(null, null, failure));
            } finally {
                this.connectingTransport = null;
            }
        });
    }

    /** Startet denselben Protokollpfad ueber einen bereits verbundenen In-Process-Transport. */
    public void connect(TransportConnection connection, String username, UUID identity) {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(identity);
        if (this.phase != Phase.IDLE && this.phase != Phase.DISCONNECTED && this.phase != Phase.FAILED) {
            throw new IllegalStateException("A client connection is already active");
        }
        closeResources(false);
        resetForConnect();
        this.pendingUsername = normalizeUsername(username);
        adoptConnection(connection, identity);
    }

    private String pendingUsername;

    /** Called by the game tick thread. */
    public void update() {
        Connected connected = this.completedConnect.getAndSet(null);
        if (connected != null && !this.cancelled) {
            if (connected.failure != null) {
                fail(readableFailure(connected.failure));
            } else {
                this.transport = connected.transport;
                adoptConnection(connected.connection, this.launchIdentity);
            }
        }

        if (this.session == null || this.cancelled) return;
        try {
            this.session.update();
            if (this.phase != Phase.PLAY && this.phase != Phase.DISCONNECTED && this.phase != Phase.FAILED) {
                this.phase = phaseOf(this.session.state());
            }
            if ((this.connection == null || !this.connection.open()) && this.phase != Phase.DISCONNECTED) {
                this.disconnectReason = DisconnectReason.INTERNAL_ERROR;
                this.detail = "Connection closed";
                this.phase = Phase.DISCONNECTED;
            }
        } catch (RuntimeException failure) {
            fail(readableFailure(failure));
        }
    }

    public Phase phase() { return this.phase; }
    public String detail() { return this.detail; }
    public DisconnectReason disconnectReason() { return this.disconnectReason; }
    public ClientNetworkSession session() { return this.session; }
    public ReplicatedChunkCache chunks() { return this.chunks; }
    public void setChunkListener(ReplicatedChunkCache.Listener listener) {
        this.chunkListener = listener;
        if (this.chunks != null) this.chunks.setListener(listener);
    }
    public CorePackets.JoinGame joinGame() { return this.joinGame; }
    public PlayerStateSnapshot playerState() { return this.playerState; }
    public UUID launchIdentity() { return this.launchIdentity; }
    public Map<Integer, CorePackets.PlayerJoined> remotePlayers() { return Map.copyOf(this.remotePlayers); }
    public int blockStateToNetwork(int localStateId) {
        return this.blockStateLocalToNetwork.applyAsInt(localStateId);
    }
    public int blockStateFromNetwork(int networkStateId) {
        return this.blockStateNetworkToLocal.applyAsInt(networkStateId);
    }
    public void drainMessages(Consumer<String> consumer) {
        String message;
        while ((message = this.receivedMessages.poll()) != null) consumer.accept(message);
    }
    public void drainOpenedContainers(Consumer<CorePackets.ContainerOpen> consumer) {
        CorePackets.ContainerOpen opened;
        while ((opened = this.openedContainers.poll()) != null) consumer.accept(opened);
    }
    public void drainClosedContainers(java.util.function.IntConsumer consumer) {
        Integer id;
        while ((id = this.closedContainers.poll()) != null) consumer.accept(id);
    }
    public void drainWorldSounds(Consumer<CorePackets.WorldSound> consumer) {
        CorePackets.WorldSound sound;
        while ((sound = this.worldSounds.poll()) != null) consumer.accept(sound);
    }
    public void drainEntityEvents(Consumer<CorePackets.EntityEvent> consumer) {
        CorePackets.EntityEvent event;
        while ((event = this.entityEvents.poll()) != null) consumer.accept(event);
    }
    public void drainBlockActionResults(Consumer<CorePackets.BlockActionResult> consumer) {
        CorePackets.BlockActionResult result;
        while ((result = this.blockActionResults.poll()) != null) consumer.accept(result);
    }
    public void drainBlockActionEffects(Consumer<CorePackets.BlockActionEffect> consumer) {
        CorePackets.BlockActionEffect effect;
        while ((effect = this.blockActionEffects.poll()) != null) consumer.accept(effect);
    }
    public void drainInventoryResults(Consumer<CorePackets.InventoryTransactionResult> consumer) {
        CorePackets.InventoryTransactionResult result;
        while ((result = this.inventoryResults.poll()) != null) consumer.accept(result);
    }
    public void drainHotbarResults(Consumer<CorePackets.SelectedHotbarSlotResult> consumer) {
        CorePackets.SelectedHotbarSlotResult result;
        while ((result = this.hotbarResults.poll()) != null) consumer.accept(result);
    }
    public int itemToNetwork(String identifier) {
        Integer id = this.itemNetworkIds.get(identifier);
        if (id == null) throw new IllegalArgumentException("Item is absent from negotiated registry: " + identifier);
        return id;
    }
    public boolean active() {
        return switch (this.phase) {
            case CONNECTING, HANDSHAKE, LOGIN, CONFIGURATION, JOINING, PLAY -> true;
            default -> false;
        };
    }

    public void disconnect() {
        this.cancelled = true;
        this.disconnectReason = DisconnectReason.CLIENT_QUIT;
        this.detail = "";
        this.phase = Phase.DISCONNECTED;
        Thread thread = this.connectThread;
        if (thread != null) thread.interrupt();
        closeResources(true);
    }

    @Override public void close() { disconnect(); }

    private void fail(String message) {
        this.detail = message;
        this.disconnectReason = DisconnectReason.INTERNAL_ERROR;
        this.phase = Phase.FAILED;
        closeResources(true);
    }

    private void closeResources(boolean asynchronous) {
        NettyClientTransport adopted = this.transport;
        this.transport = null;
        TransportConnection adoptedConnection = this.connection;
        this.connection = null;
        this.session = null;
        Connected pending = this.completedConnect.getAndSet(null);
        NettyClientTransport pendingTransport = pending == null ? null : pending.transport;
        NettyClientTransport connecting = this.connectingTransport;
        Runnable close = () -> {
            if (adoptedConnection != null) {
                try { adoptedConnection.close(); } catch (RuntimeException ignored) { }
            }
            closeQuietly(adopted);
            if (pendingTransport != adopted) closeQuietly(pendingTransport);
            if (connecting != adopted && connecting != pendingTransport) closeQuietly(connecting);
        };
        if (asynchronous && (adoptedConnection != null || adopted != null
                || pendingTransport != null || connecting != null)) {
            Thread.ofVirtual().name("Server Disconnect").start(close);
        } else close.run();
    }

    private static void closeQuietly(NettyClientTransport transport) {
        if (transport == null) return;
        try { transport.close(); } catch (RuntimeException ignored) { }
    }

    private void resetForConnect() {
        this.cancelled = false;
        this.detail = "";
        this.disconnectReason = null;
        this.joinGame = null;
        this.playerState = null;
        this.remotePlayers.clear();
        this.openedContainers.clear();
        this.closedContainers.clear();
        this.worldSounds.clear();
        this.entityEvents.clear();
        this.blockActionResults.clear();
        this.blockActionEffects.clear();
        this.inventoryResults.clear();
        this.hotbarResults.clear();
        this.itemNetworkIds.clear();
        this.blockStateLocalToNetwork = id -> id;
        this.blockStateNetworkToLocal = id -> id;
    }

    private void adoptConnection(TransportConnection connection, UUID identity) {
        this.connection = connection;
        this.chunks = new ReplicatedChunkCache(this.chunkListener);
        this.chunks.setTrustedImmutableTransfer(connection.transfersImmutableObjects());
        this.session = new ClientNetworkSession(connection, this.chunks,
                packs -> ClientNetworkSession.PackValidation.acceptAll(), new SessionListener());
        this.chunks.setResyncRequester(request -> {
            ClientNetworkSession active = this.session;
            if (active != null && active.state() == ConnectionState.PLAY) active.requestChunkResync(request);
        });
        try {
            this.session.start(this.pendingUsername, identity);
            this.phase = Phase.HANDSHAKE;
            this.detail = "";
        } catch (RuntimeException failure) {
            fail(readableFailure(failure));
        }
    }

    private static Phase phaseOf(ConnectionState state) {
        return switch (state) {
            case HANDSHAKE -> Phase.HANDSHAKE;
            case LOGIN -> Phase.LOGIN;
            case CONFIGURATION -> Phase.CONFIGURATION;
            case JOINING -> Phase.JOINING;
            case PLAY -> Phase.PLAY;
            case DISCONNECTING, CLOSED -> Phase.DISCONNECTED;
        };
    }

    private static String normalizeUsername(String username) {
        String source = username == null ? "Player" : username.trim();
        StringBuilder safe = new StringBuilder(16);
        for (int i = 0; i < source.length() && safe.length() < 16; i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') safe.append(c);
        }
        return safe.isEmpty() ? "Player" : safe.toString();
    }

    private static String readableFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof UnknownHostException) return "Unknown host";
        if (cause instanceof ConnectException) return "Connection refused";
        if (cause instanceof InterruptedException) return "Connection cancelled";
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private final class SessionListener implements ClientNetworkSession.Listener {
        @Override public void registryReceived(de.skyengine.shared.network.pack.RegistryMapping mapping) {
            if (mapping.registry().equals("block_state") && !mapping.identifiers().isEmpty()) {
                LegacyBlockStateNetworkMapper.Mapping mapper =
                        LegacyBlockStateNetworkMapper.createBidirectional(mapping);
                ClientMultiplayerConnection.this.chunks.setBlockStateMapper(
                        mapper.remoteToLocal());
                ClientMultiplayerConnection.this.blockStateLocalToNetwork = mapper.localToRemote();
                ClientMultiplayerConnection.this.blockStateNetworkToLocal = mapper.remoteToLocal();
            } else if (mapping.registry().equals("item")) {
                ClientMultiplayerConnection.this.itemNetworkIds.clear();
                for (int id = 0; id < mapping.identifiers().size(); id++) {
                    ClientMultiplayerConnection.this.itemNetworkIds.put(mapping.identifiers().get(id), id);
                }
            }
        }

        @Override public void joined(CorePackets.JoinGame packet) {
            ClientMultiplayerConnection.this.joinGame = packet;
        }

        @Override public void authoritativePlayerState(PlayerStateSnapshot state) {
            ClientMultiplayerConnection.this.playerState = state;
        }

        @Override public void selectedHotbarSlotResult(CorePackets.SelectedHotbarSlotResult result) {
            ClientMultiplayerConnection.this.hotbarResults.add(result);
        }

        @Override public void containerOpened(CorePackets.ContainerOpen opened) {
            ClientMultiplayerConnection.this.openedContainers.add(opened);
        }

        @Override public void containerClosed(CorePackets.ContainerClosed closed) {
            ClientMultiplayerConnection.this.closedContainers.add(closed.containerId());
        }

        @Override public void worldSound(CorePackets.WorldSound sound) {
            ClientMultiplayerConnection.this.worldSounds.add(sound);
        }

        @Override public void blockActionResult(CorePackets.BlockActionResult result) {
            ClientMultiplayerConnection.this.blockActionResults.add(result);
        }

        @Override public void blockActionEffect(CorePackets.BlockActionEffect effect) {
            ClientMultiplayerConnection.this.blockActionEffects.add(effect);
        }

        @Override public void inventoryTransactionResult(CorePackets.InventoryTransactionResult result) {
            ClientMultiplayerConnection.this.inventoryResults.add(result);
        }

        @Override public void entityEvent(CorePackets.EntityEvent event) {
            ClientMultiplayerConnection.this.entityEvents.add(event);
        }

        @Override public void playerJoined(CorePackets.PlayerJoined packet) {
            ClientMultiplayerConnection.this.remotePlayers.put(packet.entityId(), packet);
        }

        @Override public void playerLeft(CorePackets.PlayerLeft packet) {
            var iterator = ClientMultiplayerConnection.this.remotePlayers.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!entry.getValue().identity().equals(packet.identity())) continue;
                // Eager local cleanup complements the canonical EntityDespawn. Both are idempotent,
                // which also makes disconnect/reconnect races harmless.
                ClientMultiplayerConnection.this.session.entities().despawn(
                        new CorePackets.EntityDespawn(entry.getKey(), 0));
                iterator.remove();
            }
        }

        @Override public void chatMessage(CorePackets.ChatMessage message) {
            ClientMultiplayerConnection.this.receivedMessages.add("<" + message.senderName() + "> "
                    + message.message());
        }

        @Override public void commandResult(CorePackets.CommandResult result) {
            String color = result.success() ? "§f" : "§c";
            for (String message : result.messages()) {
                ClientMultiplayerConnection.this.receivedMessages.add(color + message);
            }
        }

        @Override public void disconnected(DisconnectReason reason, String message) {
            ClientMultiplayerConnection.this.disconnectReason = reason;
            ClientMultiplayerConnection.this.detail = message == null ? "" : message;
            ClientMultiplayerConnection.this.phase = Phase.DISCONNECTED;
        }
    }
}
