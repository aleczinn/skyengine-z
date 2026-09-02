package de.skyengine.server.network;

import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.gameplay.PlayerAbilityAction;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Objects;

public final class PlayerSession {
    private final TransportConnection connection;
    private final long connectedNanos;
    private long lastPacketReceivedNanos;
    private long lastPingSentNanos;
    private long lastPongReceivedNanos;
    private long outstandingPingNonce;
    private long roundTripNanos;
    private PlayerIdentity identity;
    private boolean packAccepted;
    private boolean handshakeAccepted;
    private int entityId;
    private long lastInputSequence;
    private long nextPlayerStateSequence;
    private long lastAbilityActionId = -1;
    private long lastHotbarActionId = -1;
    private long lastBlockActionId = -1;
    private PlayerStateSnapshot playerState;
    /** Ordered movement intents awaiting the authoritative tick owner. */
    private final ArrayDeque<PlayerInputFrame> simulationInputs = new ArrayDeque<>();
    record PendingAbility(long actionId, long inputSequence, PlayerAbilityAction action) { }
    private final ArrayDeque<PendingAbility> pendingAbilities = new ArrayDeque<>();
    private static final int MAX_PENDING_INPUTS = 256;
    private static final int MAX_PENDING_ABILITIES = 64;
    private int sentInventoryRevision = Integer.MIN_VALUE;
    private int activeContainerId;
    private int sentContainerDataHash = Integer.MIN_VALUE;
    private int interestChunkX = Integer.MIN_VALUE;
    private int interestChunkZ = Integer.MIN_VALUE;
    private String interestDimension;
    private final TokenBucket movementLimit;
    private final TokenBucket gameplayLimit;
    private final TokenBucket inventoryLimit;
    private final TokenBucket chatLimit;

    PlayerSession(TransportConnection connection, long nowNanos) {
        this.connection = Objects.requireNonNull(connection);
        this.connectedNanos = nowNanos;
        this.lastPacketReceivedNanos = nowNanos;
        this.lastPongReceivedNanos = nowNanos;
        this.movementLimit = new TokenBucket(40, 80, nowNanos);
        this.gameplayLimit = new TokenBucket(40, 80, nowNanos);
        // A single legitimate drag-distribution gesture can touch a complete 6-row container.
        // Keep flood protection, but do not disconnect Mouse-Tweaks-style batched slot actions.
        this.inventoryLimit = new TokenBucket(80, 160, nowNanos);
        this.chatLimit = new TokenBucket(4, 8, nowNanos);
    }

    public TransportConnection connection() { return this.connection; }
    public ConnectionState state() { return this.connection.state(); }
    public PlayerIdentity identity() { return this.identity; }
    void identity(PlayerIdentity value) { this.identity = value; }
    public boolean packAccepted() { return this.packAccepted; }
    void packAccepted(boolean value) { this.packAccepted = value; }
    boolean handshakeAccepted() { return this.handshakeAccepted; }
    void handshakeAccepted(boolean value) { this.handshakeAccepted = value; }
    public int entityId() { return this.entityId; }
    void entityId(int value) { this.entityId = value; }
    public long lastInputSequence() { return this.lastInputSequence; }
    void lastInputSequence(long value) { this.lastInputSequence = value; }
    public PlayerStateSnapshot playerState() { return this.playerState; }
    void playerState(PlayerStateSnapshot value) { this.playerState = value; }
    long lastAbilityActionId() { return this.lastAbilityActionId; }
    void lastAbilityActionId(long value) { this.lastAbilityActionId = value; }
    long lastHotbarActionId() { return this.lastHotbarActionId; }
    void lastHotbarActionId(long value) { this.lastHotbarActionId = value; }
    long lastBlockActionId() { return this.lastBlockActionId; }
    void lastBlockActionId(long value) { this.lastBlockActionId = value; }
    boolean enqueueSimulationInput(PlayerInputFrame value) {
        if (this.simulationInputs.size() >= MAX_PENDING_INPUTS) return false;
        this.simulationInputs.addLast(value);
        return true;
    }
    PlayerInputFrame pollSimulationInput() { return this.simulationInputs.pollFirst(); }
    int pendingSimulationInputs() { return this.simulationInputs.size(); }
    boolean enqueueAbility(long actionId, long inputSequence, PlayerAbilityAction action) {
        if (this.pendingAbilities.size() >= MAX_PENDING_ABILITIES) return false;
        this.pendingAbilities.addLast(new PendingAbility(actionId, inputSequence, action));
        return true;
    }
    java.util.List<PendingAbility> abilitiesThrough(long inputSequence) {
        java.util.List<PendingAbility> result = new java.util.ArrayList<>();
        while (!this.pendingAbilities.isEmpty()
                && this.pendingAbilities.peekFirst().inputSequence() <= inputSequence) {
            result.add(this.pendingAbilities.removeFirst());
        }
        return result;
    }
    int sentInventoryRevision() { return this.sentInventoryRevision; }
    void sentInventoryRevision(int value) { this.sentInventoryRevision = value; }
    int activeContainerId() { return this.activeContainerId; }
    void activeContainerId(int value) {
        this.activeContainerId = value;
        this.sentInventoryRevision = Integer.MIN_VALUE;
        this.sentContainerDataHash = Integer.MIN_VALUE;
    }
    int sentContainerDataHash() { return this.sentContainerDataHash; }
    void sentContainerDataHash(int value) { this.sentContainerDataHash = value; }
    boolean interestCenterChanged(String dimension, int chunkX, int chunkZ) {
        if (Objects.equals(this.interestDimension, dimension)
                && this.interestChunkX == chunkX && this.interestChunkZ == chunkZ) return false;
        this.interestDimension = dimension;
        this.interestChunkX = chunkX; this.interestChunkZ = chunkZ;
        return true;
    }
    public long connectedNanos() { return this.connectedNanos; }
    public long lastPacketReceivedNanos() { return this.lastPacketReceivedNanos; }
    void received(long nowNanos) { this.lastPacketReceivedNanos = nowNanos; }
    public long lastPingSentNanos() { return this.lastPingSentNanos; }
    void pingSent(long nowNanos, long nonce) { this.lastPingSentNanos = nowNanos; this.outstandingPingNonce = nonce; }
    public long lastPongReceivedNanos() { return this.lastPongReceivedNanos; }
    public long roundTripNanos() { return this.roundTripNanos; }
    long outstandingPingNonce() { return this.outstandingPingNonce; }
    void pong(long nowNanos) {
        this.lastPongReceivedNanos = nowNanos;
        this.roundTripNanos = Math.max(0, nowNanos - this.lastPingSentNanos);
        this.outstandingPingNonce = 0;
    }
    boolean trustedLocal() {
        return this.connection.remoteAddress() instanceof InetSocketAddress address
                && address.getPort() == 0 && address.getAddress().isLoopbackAddress();
    }
    boolean allowMovement(long nowNanos) { return this.movementLimit.tryConsume(1, nowNanos); }
    boolean allowGameplay(long nowNanos) { return this.gameplayLimit.tryConsume(1, nowNanos); }
    boolean allowInventory(long nowNanos) { return this.inventoryLimit.tryConsume(1, nowNanos); }
    boolean allowChat(long nowNanos) { return this.chatLimit.tryConsume(1, nowNanos); }
    public boolean send(de.skyengine.shared.network.Packet packet) { return this.connection.send(new PacketEnvelope(packet)); }
    public boolean send(de.skyengine.shared.network.Packet packet, long sequence) {
        return this.connection.send(new PacketEnvelope(packet, sequence));
    }
    boolean sendPlayerState(PlayerStateSnapshot state) {
        return send(new CorePackets.PlayerState(state), ++this.nextPlayerStateSequence);
    }
}
