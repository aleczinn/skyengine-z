package de.skyengine.shared.network.packets;

import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.Packet;
import de.skyengine.shared.network.pack.PackDescriptor;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.BlockEntitySnapshot;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.gameplay.BlockActionRequest;
import de.skyengine.shared.gameplay.InventoryActionRequest;
import de.skyengine.shared.gameplay.EntityActionRequest;
import de.skyengine.shared.gameplay.ContainerKind;
import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.gameplay.WorldSoundType;
import de.skyengine.shared.gameplay.BlockActionEffectType;
import de.skyengine.shared.gameplay.PlayerAbilityAction;
import de.skyengine.shared.gameplay.AuthoritativeBlockCorrection;
import de.skyengine.shared.entity.NetworkEntitySnapshot;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CorePackets {
    public record ServerStatusRequest(long nonce, int protocolVersion) implements Packet {}
    public record ServerStatusResponse(long nonce, int protocolVersion, String engineVersion,
                                       String motd, int onlinePlayers, int maxPlayers) implements Packet {
        public ServerStatusResponse {
            if (onlinePlayers < 0 || maxPlayers < 1 || onlinePlayers > maxPlayers) {
                throw new IllegalArgumentException("Invalid server player count");
            }
        }
    }
    public record Handshake(int protocolVersion, String engineVersion) implements Packet {}
    public record HandshakeAccepted(int protocolVersion, String engineVersion,
                                    List<String> compressionAlgorithms) implements Packet {
        public HandshakeAccepted { compressionAlgorithms = List.copyOf(compressionAlgorithms); }
    }
    public record CompressionSelect(String algorithm) implements Packet {}
    public record CompressionEnabled(String algorithm, int threshold, int maximumDecompressedBytes,
                                     int level) implements Packet {
        public CompressionEnabled {
            if ((!algorithm.equals("none") && !algorithm.equals("zstd")) || threshold < 0
                    || maximumDecompressedBytes < 1024 || maximumDecompressedBytes > 8 * 1024 * 1024
                    || level < -5 || level > 22) throw new IllegalArgumentException("Invalid compression settings");
        }
    }
    public record LoginStart(String username, UUID requestedIdentity) implements Packet {}
    public record LoginSuccess(UUID identity, String username) implements Packet {}
    public record LoginFailure(DisconnectReason reason, String message) implements Packet {}
    public record PackManifest(List<PackDescriptor> packs) implements Packet {
        public PackManifest { packs = List.copyOf(packs); }
    }
    public record PackStatus(boolean accepted, List<String> missingRequiredPacks) implements Packet {
        public PackStatus { missingRequiredPacks = List.copyOf(missingRequiredPacks); }
    }
    public record RegistryFingerprint(byte[] sha256) implements Packet {
        public RegistryFingerprint {
            if (sha256 == null || sha256.length != 32) throw new IllegalArgumentException("Fingerprint must be SHA-256");
            sha256 = sha256.clone();
        }
        @Override public byte[] sha256() { return this.sha256.clone(); }
    }
    public record RegistryData(RegistryMapping mapping) implements Packet {}
    public record ConfigurationAck(byte[] registryFingerprint) implements Packet {
        public ConfigurationAck {
            if (registryFingerprint == null || registryFingerprint.length != 32) {
                throw new IllegalArgumentException("Fingerprint must be SHA-256");
            }
            registryFingerprint = registryFingerprint.clone();
        }
        @Override public byte[] registryFingerprint() { return this.registryFingerprint.clone(); }
    }
    public record JoinGame(UUID identity, int playerEntityId, String dimension, long serverTick,
                           int ticksPerSecond, int viewDistance, int simulationDistance) implements Packet {}
    public record ClientReady(long lastAppliedChunkBatch) implements Packet {}
    public record PlayerInput(PlayerInputFrame input) implements Packet {}
    /** Reliable edge paired with the movement input that predicts it locally. */
    public record PlayerAbility(long actionId, long inputSequence, PlayerAbilityAction action) implements Packet {
        public PlayerAbility {
            Objects.requireNonNull(action);
            if (actionId < 0 || inputSequence < 1) throw new IllegalArgumentException("Invalid player ability");
        }
    }
    /** Reliable selected-slot intent; movement snapshots carry the slot only as redundancy. */
    public record SelectedHotbarSlot(long actionId, int slot) implements Packet {
        public SelectedHotbarSlot {
            if (actionId < 0 || slot < 0 || slot > 8) {
                throw new IllegalArgumentException("Invalid selected hotbar slot");
            }
        }
    }
    public record SelectedHotbarSlotResult(long actionId, int slot) implements Packet {
        public SelectedHotbarSlotResult {
            if (actionId < 0 || slot < 0 || slot > 8) {
                throw new IllegalArgumentException("Invalid selected hotbar slot result");
            }
        }
    }
    /** Presentation intent; the server remains authoritative and fans the animation out to observers. */
    public record PlayerSwing(long actionId) implements Packet {
        public PlayerSwing {
            if (actionId < 0) throw new IllegalArgumentException("Negative swing action ID");
        }
    }
    public record PlayerState(PlayerStateSnapshot state) implements Packet {}
    public record BlockAction(BlockActionRequest request) implements Packet {}
    public record BlockActionResult(long actionId, boolean accepted, String message,
                                    List<AuthoritativeBlockCorrection> corrections) implements Packet {
        public BlockActionResult {
            corrections = List.copyOf(corrections);
            if (corrections.size() > 4) throw new IllegalArgumentException("Too many block corrections");
        }
        public BlockActionResult(long actionId, boolean accepted, String message) {
            this(actionId, accepted, message, List.of());
        }
    }
    public record BlockActionEffect(long actionId, int sourceEntityId, BlockActionEffectType type,
                                    String dimension, int stateId, int x, int y, int z,
                                    int face, int hitX, int hitY, int hitZ) implements Packet {
        public BlockActionEffect {
            Objects.requireNonNull(type); Objects.requireNonNull(dimension);
            if (actionId < 0 || sourceEntityId <= 0 || stateId < 0 || y < 0 || y >= 512
                    || face < 0 || face > 5 || hitX < 0 || hitX > 255
                    || hitY < 0 || hitY > 255 || hitZ < 0 || hitZ > 255) {
                throw new IllegalArgumentException("Invalid block action effect");
            }
        }
    }
    public record EntityAction(EntityActionRequest request) implements Packet {}
    public record EntityActionResult(long actionId, boolean accepted, String message) implements Packet {}
    public record InventoryAction(InventoryActionRequest request) implements Packet {}
    public record InventoryTransactionResult(long transactionId, boolean accepted, String message)
            implements Packet {}
    public record InventorySlotUpdate(int containerId, int revision, int slot, NetworkItemStack stack)
            implements Packet {}
    public record InventoryContent(int containerId, int revision, List<NetworkItemStack> stacks,
                                   NetworkItemStack carried) implements Packet {
        public InventoryContent { stacks = List.copyOf(stacks); }
    }
    public record ContainerOpen(int containerId, ContainerKind kind, int containerSlots, int rows,
                                String dimension, int x, int y, int z) implements Packet {
        public ContainerOpen {
            Objects.requireNonNull(kind); Objects.requireNonNull(dimension);
            if (containerId <= 0 || containerSlots <= 0 || containerSlots > 4096
                    || rows < 0 || rows > 64) throw new IllegalArgumentException("Invalid container descriptor");
        }
    }
    public record ContainerClose(int containerId) implements Packet {
        public ContainerClose {
            if (containerId <= 0) throw new IllegalArgumentException("Invalid container id");
        }
    }
    public record ContainerClosed(int containerId) implements Packet {
        public ContainerClosed {
            if (containerId <= 0) throw new IllegalArgumentException("Invalid container id");
        }
    }
    public record ContainerData(int containerId, int[] values) implements Packet {
        public ContainerData {
            if (containerId <= 0 || values == null || values.length > 64) {
                throw new IllegalArgumentException("Invalid container data");
            }
            values = values.clone();
        }
        @Override public int[] values() { return this.values.clone(); }
    }
    public record ContainerOpenRequest() implements Packet { }
    /** Requests the authoritative server respawn after the current player has died. */
    public record RespawnRequest() implements Packet { }
    public record WorldSound(String dimension, WorldSoundType type, int data,
                             double x, double y, double z) implements Packet {
        public WorldSound { Objects.requireNonNull(dimension); Objects.requireNonNull(type); }
    }
    public record ChatMessageRequest(String message) implements Packet {}
    public record ChatMessage(UUID sender, String senderName, long serverTick, String message) implements Packet {}
    public record CommandRequest(long commandId, String command) implements Packet {}
    public record CommandResult(long commandId, boolean success, List<String> messages) implements Packet {
        public CommandResult { messages = List.copyOf(messages); }
    }
    public record EntitySpawn(NetworkEntitySnapshot entity) implements Packet {}
    public record EntityDespawn(int networkId, int reason) implements Packet {}
    public record EntityState(long serverTick, NetworkEntitySnapshot entity) implements Packet {}
    public record EntityMetadata(int networkId, long revision, byte[] metadata) implements Packet {
        public EntityMetadata {
            metadata = metadata.clone();
            if (metadata.length > NetworkEntitySnapshot.MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("Entity metadata too large");
            }
        }
        @Override public byte[] metadata() { return this.metadata.clone(); }
    }
    public record EntityEvent(int networkId, int eventId, int data) implements Packet {}
    public record ChunkViewUpdate(String dimension, int centerChunkX, int centerChunkZ,
                                  int viewDistance, int meshHalo, long epoch) implements Packet {
        public ChunkViewUpdate {
            Objects.requireNonNull(dimension);
            if (viewDistance < 0 || viewDistance > 32 || meshHalo < 0 || meshHalo > 1 || epoch < 0) {
                throw new IllegalArgumentException("Invalid chunk view");
            }
        }
    }
    public record ChunkBatchStart(long batchId, long leaseId, long viewEpoch, String dimension,
                                  int centerChunkX, int centerChunkZ, int chunkCount) implements Packet {
        public ChunkBatchStart {
            if (batchId < 0) throw new IllegalArgumentException("Negative chunk batch ID");
            if (leaseId < 0) throw new IllegalArgumentException("Negative chunk lease ID");
            if (viewEpoch < 0) throw new IllegalArgumentException("Negative chunk view epoch");
            Objects.requireNonNull(dimension);
        }
        public ChunkBatchStart(long batchId, long leaseId, String dimension,
                               int centerChunkX, int centerChunkZ, int chunkCount) {
            this(batchId, leaseId, 0, dimension, centerChunkX, centerChunkZ, chunkCount);
        }
    }
    public record ChunkColumnData(long batchId, ChunkColumnSnapshot chunk) implements Packet {}
    /** Bounded transport fragment of a canonical ChunkColumnSnapshot payload. */
    public static final class ChunkColumnFragment implements Packet {
        /* Large enough for the common complete column to stay in one frame, still well below
           the protocol's 2 MiB frame ceiling. Fewer fragments mean fewer array copies and
           fewer per-packet queue/codec operations during the initial L0 burst. */
        public static final int MAX_FRAGMENT_BYTES = 512 * 1024;
        private final long batchId;
        private final int fragmentIndex;
        private final int fragmentCount;
        private final int totalLength;
        private final int decodedLength;
        private final de.skyengine.shared.world.ImmutableByteArray data;

        public ChunkColumnFragment(long batchId, int fragmentIndex, int fragmentCount,
                                   int totalLength, byte[] data) {
            this(batchId, fragmentIndex, fragmentCount, totalLength, 0,
                    de.skyengine.shared.world.ImmutableByteArray.copyOf(
                            data == null ? new byte[0] : data));
        }

        private ChunkColumnFragment(long batchId, int fragmentIndex, int fragmentCount,
                                    int totalLength, int decodedLength,
                                    de.skyengine.shared.world.ImmutableByteArray data) {
            if (batchId < 0 || fragmentIndex < 0 || fragmentCount < 1 || fragmentCount > 256
                    || fragmentIndex >= fragmentCount || totalLength < 1
                    || totalLength > de.skyengine.shared.network.ProtocolLimits.MAX_DECOMPRESSED_BYTES
                    || decodedLength < 0
                    || decodedLength > de.skyengine.shared.network.ProtocolLimits.MAX_DECOMPRESSED_BYTES
                    || data.length() < 1 || data.length() > MAX_FRAGMENT_BYTES) {
                throw new IllegalArgumentException("Invalid chunk fragment");
            }
            this.batchId = batchId;
            this.fragmentIndex = fragmentIndex;
            this.fragmentCount = fragmentCount;
            this.totalLength = totalLength;
            this.decodedLength = decodedLength;
            this.data = data;
        }

        public static ChunkColumnFragment takeOwnership(long batchId, int fragmentIndex,
                                                        int fragmentCount, int totalLength,
                                                        byte[] data) {
            return takeOwnership(batchId, fragmentIndex, fragmentCount, totalLength, 0, data);
        }

        public static ChunkColumnFragment takeOwnership(long batchId, int fragmentIndex,
                                                        int fragmentCount, int totalLength,
                                                        int decodedLength, byte[] data) {
            return new ChunkColumnFragment(batchId, fragmentIndex, fragmentCount, totalLength, decodedLength,
                    de.skyengine.shared.world.ImmutableByteArray.takeOwnership(data));
        }

        /** Shares an already immutable payload slice without manufacturing an intermediate array. */
        public static ChunkColumnFragment shared(long batchId, int fragmentIndex,
                                                 int fragmentCount, int totalLength,
                                                 int decodedLength,
                                                 de.skyengine.shared.world.ImmutableByteArray data) {
            return new ChunkColumnFragment(batchId, fragmentIndex, fragmentCount, totalLength,
                    decodedLength, java.util.Objects.requireNonNull(data));
        }

        public long batchId() { return this.batchId; }
        public int fragmentIndex() { return this.fragmentIndex; }
        public int fragmentCount() { return this.fragmentCount; }
        public int totalLength() { return this.totalLength; }
        public int decodedLength() { return this.decodedLength; }
        public byte[] data() { return this.data.copy(); }
        public de.skyengine.shared.world.ImmutableByteArray dataPayload() { return this.data; }
        public java.nio.ByteBuffer dataView() {
            return this.data.readOnlyView();
        }
    }
    public record ChunkBatchEnd(long batchId) implements Packet {}
    /** Confirms that the complete batch is installed in the replicated CPU chunk cache. */
    public record ChunkBatchApplied(long batchId, long leaseId) implements Packet {
        public ChunkBatchApplied {
            if (batchId < 0) throw new IllegalArgumentException("Negative chunk batch ID");
            if (leaseId < 0) throw new IllegalArgumentException("Negative chunk lease ID");
        }
    }
    public record UnloadChunk(long leaseId, String dimension, int chunkX, int chunkZ) implements Packet {
        public UnloadChunk {
            if (leaseId < 0) throw new IllegalArgumentException("Negative chunk lease ID");
            Objects.requireNonNull(dimension);
        }
    }
    public record ChunkResyncRequest(String dimension, int chunkX, int chunkZ,
                                     long knownRevision) implements Packet {
        public ChunkResyncRequest {
            Objects.requireNonNull(dimension);
            if (knownRevision < 0) throw new IllegalArgumentException("Negative chunk revision");
        }
    }
    public record BlockUpdate(String dimension, int chunkX, int chunkZ, long revision,
                              BlockChange change) implements Packet {}
    public record MultiBlockUpdate(String dimension, int chunkX, int chunkZ, long revision,
                                   List<BlockChange> changes) implements Packet {
        public MultiBlockUpdate { changes = List.copyOf(changes); }
    }
    public record BlockEntityUpdate(String dimension, int chunkX, int chunkZ,
                                    BlockEntitySnapshot blockEntity) implements Packet {
        public BlockEntityUpdate {
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(blockEntity);
        }
    }
    public record PlayerJoined(UUID identity, String username, int entityId) implements Packet {}
    public record PlayerLeft(UUID identity, DisconnectReason reason) implements Packet {}
    public record KeepAlive(long nonce, long sentNanos) implements Packet {}
    public record KeepAliveResponse(long nonce) implements Packet {}
    public record Disconnect(DisconnectReason reason, String message) implements Packet {}

    private CorePackets() {}
}
