package de.skyengine.server.network;

import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.player.PlayerInputFrame;

import java.net.InetSocketAddress;
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
    private PlayerStateSnapshot playerState;
    private PlayerInputFrame simulationInput;
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
    void simulationInput(PlayerInputFrame value) {
        int oneShots = this.simulationInput == null ? 0 : this.simulationInput.buttons()
                & (PlayerInputFrame.CYCLE_GAME_MODE | PlayerInputFrame.TOGGLE_FLY);
        this.simulationInput = oneShots == 0 ? value : new PlayerInputFrame(value.sequence(),
                value.clientTick(), value.forward(), value.strafe(), value.yaw(), value.pitch(),
                value.buttons() | oneShots, value.selectedHotbarSlot());
    }
    PlayerInputFrame simulationInput() { return this.simulationInput; }
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
    void clearOneShotInputButtons() {
        if (this.simulationInput == null) return;
        int persistent = this.simulationInput.buttons()
                & ~(PlayerInputFrame.CYCLE_GAME_MODE | PlayerInputFrame.TOGGLE_FLY);
        this.simulationInput = new PlayerInputFrame(this.simulationInput.sequence(),
                this.simulationInput.clientTick(), this.simulationInput.forward(),
                this.simulationInput.strafe(), this.simulationInput.yaw(), this.simulationInput.pitch(),
                persistent, this.simulationInput.selectedHotbarSlot());
    }
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
