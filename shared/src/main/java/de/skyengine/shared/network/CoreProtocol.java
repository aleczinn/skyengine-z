package de.skyengine.shared.network;

import de.skyengine.shared.network.pack.PackDescriptor;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.BlockEntitySnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;
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
import de.skyengine.shared.entity.NetworkEntitySnapshot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

/** Canonical packet table shared by local and socket transports. */
public final class CoreProtocol {
    public static PacketRegistry createRegistry() {
        PacketRegistry registry = new PacketRegistry();
        registerHandshake(registry);
        registerLogin(registry);
        registerConfiguration(registry);
        registerJoinAndPlay(registry);
        registerChunkStreaming(registry);
        registerMovement(registry);
        registerGameplay(registry);
        registerEntities(registry);
        registerLifecycle(registry);
        registry.freeze();
        return registry;
    }

    private static void registerHandshake(PacketRegistry registry) {
        registry.register(type(2, CorePackets.ServerStatusRequest.class, PacketDirection.CLIENT_TO_SERVER,
                states(ConnectionState.HANDSHAKE), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 24,
                PacketCodec.of((out, p) -> {
                    out.writeLong(p.nonce());
                    out.writeVarInt(p.protocolVersion());
                }, in -> new CorePackets.ServerStatusRequest(in.readLong(), in.readVarInt()))));
        registry.register(type(2, CorePackets.ServerStatusResponse.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.HANDSHAKE), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 1024,
                PacketCodec.of((out, p) -> {
                    out.writeLong(p.nonce());
                    out.writeVarInt(p.protocolVersion());
                    out.writeString(p.engineVersion(), 128);
                    out.writeString(p.motd(), 512);
                    out.writeVarInt(p.onlinePlayers());
                    out.writeVarInt(p.maxPlayers());
                }, in -> new CorePackets.ServerStatusResponse(in.readLong(), in.readVarInt(),
                        in.readString(128), in.readString(512), in.readVarInt(), in.readVarInt()))));
        registry.register(type(0, CorePackets.Handshake.class, PacketDirection.CLIENT_TO_SERVER,
                states(ConnectionState.HANDSHAKE), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 512,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.protocolVersion());
                    out.writeString(p.engineVersion(), 128);
                }, in -> new CorePackets.Handshake(in.readVarInt(), in.readString(128)))));
        registry.register(type(0, CorePackets.HandshakeAccepted.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.HANDSHAKE), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 1024,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.protocolVersion());
                    out.writeString(p.engineVersion(), 128);
                    writeStrings(out, p.compressionAlgorithms(), 16, 32);
                }, in -> new CorePackets.HandshakeAccepted(in.readVarInt(), in.readString(128),
                        readStrings(in, 16, 32)))));
        registry.register(type(1, CorePackets.CompressionSelect.class, PacketDirection.CLIENT_TO_SERVER,
                states(ConnectionState.HANDSHAKE), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 32,
                PacketCodec.of((out, p) -> out.writeString(p.algorithm(), 16),
                        in -> new CorePackets.CompressionSelect(in.readString(16)))));
        registry.register(type(1, CorePackets.CompressionEnabled.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.HANDSHAKE), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 64,
                PacketCodec.of((out, p) -> {
                    out.writeString(p.algorithm(), 16); out.writeVarInt(p.threshold());
                    out.writeVarInt(p.maximumDecompressedBytes()); out.writeVarInt(p.level() + 5);
                }, in -> new CorePackets.CompressionEnabled(in.readString(16), in.readVarInt(),
                        in.readVarInt(), in.readVarInt() - 5))));
    }

    private static void registerLogin(PacketRegistry registry) {
        registry.register(type(0, CorePackets.LoginStart.class, PacketDirection.CLIENT_TO_SERVER,
                states(ConnectionState.LOGIN), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 256,
                PacketCodec.of((out, p) -> {
                    out.writeString(p.username(), ProtocolLimits.MAX_USERNAME_BYTES);
                    out.writeBoolean(p.requestedIdentity() != null);
                    if (p.requestedIdentity() != null) out.writeUuid(p.requestedIdentity());
                }, in -> new CorePackets.LoginStart(in.readString(ProtocolLimits.MAX_USERNAME_BYTES),
                        in.readBoolean() ? in.readUuid() : null))));
        registry.register(type(0, CorePackets.LoginSuccess.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.LOGIN), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 256,
                PacketCodec.of((out, p) -> {
                    out.writeUuid(p.identity());
                    out.writeString(p.username(), ProtocolLimits.MAX_USERNAME_BYTES);
                }, in -> new CorePackets.LoginSuccess(in.readUuid(), in.readString(ProtocolLimits.MAX_USERNAME_BYTES)))));
        registry.register(type(1, CorePackets.LoginFailure.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.LOGIN), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 1024,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.reason().ordinal());
                    out.writeString(p.message(), ProtocolLimits.MAX_MESSAGE_BYTES);
                }, in -> new CorePackets.LoginFailure(readDisconnectReason(in),
                        in.readString(ProtocolLimits.MAX_MESSAGE_BYTES)))));
    }

    private static void registerConfiguration(PacketRegistry registry) {
        registry.register(type(0, CorePackets.PackManifest.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.CONFIGURATION), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED,
                256 * 1024, PacketCodec.of((out, p) -> writePacks(out, p.packs()),
                        in -> new CorePackets.PackManifest(readPacks(in)))));
        registry.register(type(0, CorePackets.PackStatus.class, PacketDirection.CLIENT_TO_SERVER,
                states(ConnectionState.CONFIGURATION), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED,
                64 * 1024, PacketCodec.of((out, p) -> {
                    out.writeBoolean(p.accepted());
                    writeStrings(out, p.missingRequiredPacks(), ProtocolLimits.MAX_PACKS,
                            ProtocolLimits.MAX_IDENTIFIER_BYTES);
                }, in -> new CorePackets.PackStatus(in.readBoolean(), readStrings(in,
                        ProtocolLimits.MAX_PACKS, ProtocolLimits.MAX_IDENTIFIER_BYTES)))));
        registry.register(type(1, CorePackets.RegistryFingerprint.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.CONFIGURATION), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 32,
                PacketCodec.of((out, p) -> out.writeRawBytes(p.sha256()),
                        in -> new CorePackets.RegistryFingerprint(in.readRawBytes(32)))));
        registry.register(type(2, CorePackets.RegistryData.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.CONFIGURATION), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED,
                ProtocolLimits.MAX_FRAME_BYTES - 64, PacketCodec.of((out, p) -> {
                    RegistryMapping mapping = p.mapping();
                    out.writeString(mapping.registry(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    writeStrings(out, mapping.identifiers(), ProtocolLimits.MAX_REGISTRY_ENTRIES,
                            ProtocolLimits.MAX_IDENTIFIER_BYTES);
                }, in -> new CorePackets.RegistryData(new RegistryMapping(
                        in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES),
                        readStrings(in, ProtocolLimits.MAX_REGISTRY_ENTRIES, ProtocolLimits.MAX_IDENTIFIER_BYTES))))));
        registry.register(type(1, CorePackets.ConfigurationAck.class, PacketDirection.CLIENT_TO_SERVER,
                states(ConnectionState.CONFIGURATION), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 32,
                PacketCodec.of((out, p) -> out.writeRawBytes(p.registryFingerprint()),
                        in -> new CorePackets.ConfigurationAck(in.readRawBytes(32)))));
    }

    private static void registerJoinAndPlay(PacketRegistry registry) {
        registry.register(type(0, CorePackets.JoinGame.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.JOINING), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 512,
                PacketCodec.of((out, p) -> {
                    out.writeUuid(p.identity()); out.writeVarInt(p.playerEntityId());
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeVarLong(p.serverTick()); out.writeVarInt(p.ticksPerSecond());
                    out.writeVarInt(p.viewDistance()); out.writeVarInt(p.simulationDistance());
                }, in -> new CorePackets.JoinGame(in.readUuid(), in.readVarInt(),
                        in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readVarLong(), in.readVarInt(),
                        in.readVarInt(), in.readVarInt()))));
        registry.register(type(0, CorePackets.ClientReady.class, PacketDirection.CLIENT_TO_SERVER,
                states(ConnectionState.JOINING), LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 16,
                PacketCodec.of((out, p) -> out.writeVarLong(p.lastAppliedChunkBatch()),
                        in -> new CorePackets.ClientReady(in.readVarLong()))));
        registry.register(type(0, CorePackets.PlayerJoined.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.PLAY), LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 256,
                PacketCodec.of((out, p) -> { out.writeUuid(p.identity());
                    out.writeString(p.username(), ProtocolLimits.MAX_USERNAME_BYTES); out.writeVarInt(p.entityId()); },
                        in -> new CorePackets.PlayerJoined(in.readUuid(),
                                in.readString(ProtocolLimits.MAX_USERNAME_BYTES), in.readVarInt()))));
        registry.register(type(1, CorePackets.PlayerLeft.class, PacketDirection.SERVER_TO_CLIENT,
                states(ConnectionState.PLAY), LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 64,
                PacketCodec.of((out, p) -> { out.writeUuid(p.identity()); out.writeVarInt(p.reason().ordinal()); },
                        in -> new CorePackets.PlayerLeft(in.readUuid(), readDisconnectReason(in)))));
    }

    private static void registerLifecycle(PacketRegistry registry) {
        EnumSet<ConnectionState> active = states(ConnectionState.LOGIN, ConnectionState.CONFIGURATION,
                ConnectionState.JOINING, ConnectionState.PLAY);
        registry.register(type(100, CorePackets.KeepAlive.class, PacketDirection.SERVER_TO_CLIENT, active,
                LogicalChannel.CONTROL, DeliveryClass.RELIABLE_UNORDERED, 16,
                PacketCodec.of((out, p) -> { out.writeLong(p.nonce()); out.writeLong(p.sentNanos()); },
                        in -> new CorePackets.KeepAlive(in.readLong(), in.readLong()))));
        registry.register(type(100, CorePackets.KeepAliveResponse.class, PacketDirection.CLIENT_TO_SERVER, active,
                LogicalChannel.CONTROL, DeliveryClass.RELIABLE_UNORDERED, 8,
                PacketCodec.of((out, p) -> out.writeLong(p.nonce()),
                        in -> new CorePackets.KeepAliveResponse(in.readLong()))));
        EnumSet<ConnectionState> closable = states(ConnectionState.HANDSHAKE, ConnectionState.LOGIN,
                ConnectionState.CONFIGURATION, ConnectionState.JOINING, ConnectionState.PLAY,
                ConnectionState.DISCONNECTING);
        PacketCodec<CorePackets.Disconnect> codec = PacketCodec.of((out, p) -> {
            out.writeVarInt(p.reason().ordinal());
            out.writeString(p.message(), ProtocolLimits.MAX_MESSAGE_BYTES);
        }, in -> new CorePackets.Disconnect(readDisconnectReason(in),
                in.readString(ProtocolLimits.MAX_MESSAGE_BYTES)));
        registry.register(type(101, CorePackets.Disconnect.class, PacketDirection.SERVER_TO_CLIENT, closable,
                LogicalChannel.CONTROL, DeliveryClass.RELIABLE_ORDERED, 4096, codec));
    }

    private static void registerChunkStreaming(PacketRegistry registry) {
        EnumSet<ConnectionState> play = states(ConnectionState.PLAY);
        registry.register(type(20, CorePackets.ChunkBatchStart.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.CHUNK_DATA, DeliveryClass.RELIABLE_ORDERED, 512,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.batchId());
                    out.writeVarLong(p.leaseId());
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeInt(p.centerChunkX());
                    out.writeInt(p.centerChunkZ());
                    out.writeVarInt(p.chunkCount());
                }, in -> new CorePackets.ChunkBatchStart(in.readVarLong(), in.readVarLong(),
                        in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readInt(), in.readInt(),
                        checkedCount(in, 0, 4225, "chunk batch")))));
        registry.register(type(21, CorePackets.ChunkColumnData.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.CHUNK_DATA, DeliveryClass.RELIABLE_ORDERED,
                ProtocolLimits.MAX_FRAME_BYTES - 32,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.batchId());
                    writeChunk(out, p.chunk());
                }, in -> new CorePackets.ChunkColumnData(in.readVarLong(), readChunk(in)))));
        registry.register(type(27, CorePackets.ChunkColumnFragment.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.CHUNK_DATA, DeliveryClass.RELIABLE_ORDERED,
                CorePackets.ChunkColumnFragment.MAX_FRAGMENT_BYTES + 64,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.batchId()); out.writeVarInt(p.fragmentIndex());
                    out.writeVarInt(p.fragmentCount()); out.writeVarInt(p.totalLength());
                    java.nio.ByteBuffer data = p.dataView();
                    out.writeVarInt(data.remaining());
                    out.writeRawBytes(data);
                }, in -> {
                    try { return new CorePackets.ChunkColumnFragment(in.readVarLong(), in.readVarInt(),
                            in.readVarInt(), in.readVarInt(),
                            in.readByteArray(CorePackets.ChunkColumnFragment.MAX_FRAGMENT_BYTES)); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid chunk fragment", e); }
                })));
        registry.register(type(22, CorePackets.ChunkBatchEnd.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.CHUNK_DATA, DeliveryClass.RELIABLE_ORDERED, 16,
                PacketCodec.of((out, p) -> out.writeVarLong(p.batchId()),
                        in -> new CorePackets.ChunkBatchEnd(in.readVarLong()))));
        registry.register(type(28, CorePackets.ChunkBatchApplied.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.CHUNK_DATA, DeliveryClass.RELIABLE_ORDERED, 24,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.batchId()); out.writeVarLong(p.leaseId());
                }, in -> {
                    try { return new CorePackets.ChunkBatchApplied(in.readVarLong(), in.readVarLong()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid chunk ack", e); }
                })));
        registry.register(type(23, CorePackets.UnloadChunk.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.CHUNK_DATA, DeliveryClass.RELIABLE_ORDERED, 272,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.leaseId());
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeInt(p.chunkX()); out.writeInt(p.chunkZ());
                }, in -> new CorePackets.UnloadChunk(in.readVarLong(),
                        in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readInt(), in.readInt()))));
        registry.register(type(11, CorePackets.ChunkResyncRequest.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 288,
                PacketCodec.of((out, p) -> {
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeInt(p.chunkX()); out.writeInt(p.chunkZ()); out.writeVarLong(p.knownRevision());
                }, in -> new CorePackets.ChunkResyncRequest(
                        in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readInt(), in.readInt(),
                        in.readVarLong()))));
        registry.register(type(24, CorePackets.BlockUpdate.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 320,
                PacketCodec.of((out, p) -> {
                    writeChunkUpdateHeader(out, p.dimension(), p.chunkX(), p.chunkZ(), p.revision());
                    writeBlockChange(out, p.change());
                }, in -> new CorePackets.BlockUpdate(in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES),
                        in.readInt(), in.readInt(), in.readVarLong(), readBlockChange(in)))));
        registry.register(type(25, CorePackets.MultiBlockUpdate.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 256 * 1024,
                PacketCodec.of((out, p) -> {
                    writeChunkUpdateHeader(out, p.dimension(), p.chunkX(), p.chunkZ(), p.revision());
                    if (p.changes().size() > 32_768) throw new ProtocolException("Too many block changes");
                    out.writeVarInt(p.changes().size());
                    for (BlockChange change : p.changes()) writeBlockChange(out, change);
                }, in -> {
                    String dimension = in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    int chunkX = in.readInt(), chunkZ = in.readInt();
                    long revision = in.readVarLong();
                    int count = checkedCount(in, 1, 32_768, "block changes");
                    List<BlockChange> changes = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) changes.add(readBlockChange(in));
                    return new CorePackets.MultiBlockUpdate(dimension, chunkX, chunkZ, revision, changes);
                })));
        registry.register(type(26, CorePackets.BlockEntityUpdate.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED,
                BlockEntitySnapshot.MAX_DATA_BYTES + 512,
                PacketCodec.of((out, p) -> {
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeInt(p.chunkX()); out.writeInt(p.chunkZ());
                    writeBlockEntity(out, p.blockEntity());
                }, in -> new CorePackets.BlockEntityUpdate(
                        in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readInt(), in.readInt(),
                        readBlockEntity(in)))));
    }

    private static void registerMovement(PacketRegistry registry) {
        EnumSet<ConnectionState> play = states(ConnectionState.PLAY);
        registry.register(type(0, CorePackets.PlayerInput.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.MOVEMENT, DeliveryClass.UNRELIABLE_SEQUENCED, 48,
                PacketCodec.of((out, p) -> {
                    PlayerInputFrame input = p.input();
                    out.writeVarLong(input.sequence()); out.writeVarLong(input.clientTick());
                    out.writeByte(Math.round(input.forward() * 127));
                    out.writeByte(Math.round(input.strafe() * 127));
                    out.writeFloat(input.yaw()); out.writeFloat(input.pitch()); out.writeVarInt(input.buttons());
                    out.writeByte(input.selectedHotbarSlot());
                }, in -> {
                    long sequence = in.readVarLong(), clientTick = in.readVarLong();
                    float forward = in.readByte() / 127.0f, strafe = in.readByte() / 127.0f;
                    try {
                        return new CorePackets.PlayerInput(new PlayerInputFrame(sequence, clientTick, forward,
                                strafe, in.readFloat(), in.readFloat(), in.readVarInt(),
                                in.readUnsignedByte()));
                    } catch (IllegalArgumentException e) {
                        throw new ProtocolException("Invalid player input", e);
                    }
                })));
        registry.register(type(10, CorePackets.PlayerAbility.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.MOVEMENT, DeliveryClass.RELIABLE_ORDERED, 24,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.actionId()); out.writeVarLong(p.inputSequence());
                    out.writeByte(p.action().ordinal());
                }, in -> {
                    long actionId = in.readVarLong(), inputSequence = in.readVarLong();
                    PlayerAbilityAction action = enumValue(PlayerAbilityAction.values(),
                            in.readUnsignedByte(), "player ability");
                    try { return new CorePackets.PlayerAbility(actionId, inputSequence, action); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid player ability", e); }
                })));
        registry.register(type(10, CorePackets.PlayerState.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.MOVEMENT, DeliveryClass.UNRELIABLE_SEQUENCED, 128,
                PacketCodec.of((out, p) -> writePlayerState(out, p.state()),
                        in -> new CorePackets.PlayerState(readPlayerState(in)))));
        registry.register(type(12, CorePackets.SelectedHotbarSlot.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 16,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.actionId()); out.writeByte(p.slot());
                }, in -> {
                    try { return new CorePackets.SelectedHotbarSlot(in.readVarLong(), in.readUnsignedByte()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid hotbar selection", e); }
                })));
        registry.register(type(62, CorePackets.SelectedHotbarSlotResult.class,
                PacketDirection.SERVER_TO_CLIENT, play, LogicalChannel.GAMEPLAY,
                DeliveryClass.RELIABLE_ORDERED, 16,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.actionId()); out.writeByte(p.slot());
                }, in -> {
                    try { return new CorePackets.SelectedHotbarSlotResult(in.readVarLong(), in.readUnsignedByte()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid hotbar result", e); }
                })));
    }

    private static void registerGameplay(PacketRegistry registry) {
        EnumSet<ConnectionState> play = states(ConnectionState.PLAY);
        registry.register(type(9, CorePackets.PlayerSwing.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 16,
                PacketCodec.of((out, p) -> out.writeVarLong(p.actionId()), in -> {
                    try { return new CorePackets.PlayerSwing(in.readVarLong()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid swing", e); }
                })));
        registry.register(type(1, CorePackets.BlockAction.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 384,
                PacketCodec.of((out, p) -> {
                    BlockActionRequest request = p.request();
                    out.writeVarLong(request.actionId()); out.writeByte(request.action().ordinal());
                    out.writeString(request.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeInt(request.x()); out.writeShort(request.y()); out.writeInt(request.z());
                    out.writeByte(request.face()); out.writeByte(request.hand());
                    out.writeVarInt(request.expectedStateId());
                    out.writeVarInt(request.requestedStateId() + 1);
                    out.writeVarInt(request.expectedTargetStateId() + 1);
                    out.writeByte(request.hitX()); out.writeByte(request.hitY()); out.writeByte(request.hitZ());
                    out.writeBoolean(request.secondaryUse());
                }, in -> {
                    long actionId = in.readVarLong();
                    BlockActionRequest.Action action = enumValue(BlockActionRequest.Action.values(),
                            in.readUnsignedByte(), "block action");
                    try {
                        return new CorePackets.BlockAction(new BlockActionRequest(actionId, action,
                                in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readInt(),
                                in.readUnsignedShort(), in.readInt(), in.readUnsignedByte(),
                                in.readUnsignedByte(), in.readVarInt(), in.readVarInt() - 1,
                                in.readVarInt() - 1,
                                in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte(),
                                in.readBoolean()));
                    } catch (IllegalArgumentException e) { throw new ProtocolException("Invalid block action", e); }
                })));
        registry.register(type(40, CorePackets.BlockActionResult.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 4096,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.actionId()); out.writeBoolean(p.accepted());
                    out.writeString(p.message(), ProtocolLimits.MAX_MESSAGE_BYTES);
                    out.writeVarInt(p.corrections().size());
                    for (var correction : p.corrections()) {
                        out.writeString(correction.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                        out.writeInt(correction.x()); out.writeShort(correction.y()); out.writeInt(correction.z());
                        out.writeVarInt(correction.stateId());
                    }
                }, in -> {
                    long id = in.readVarLong(); boolean accepted = in.readBoolean();
                    String message = in.readString(ProtocolLimits.MAX_MESSAGE_BYTES);
                    int count = checkedCount(in, 0, 4, "block corrections");
                    List<de.skyengine.shared.gameplay.AuthoritativeBlockCorrection> corrections =
                            new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        corrections.add(new de.skyengine.shared.gameplay.AuthoritativeBlockCorrection(
                                in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readInt(),
                                in.readUnsignedShort(), in.readInt(), in.readVarInt()));
                    }
                    return new CorePackets.BlockActionResult(id, accepted, message, corrections);
                })));
        registry.register(type(61, CorePackets.BlockActionEffect.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 128,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.actionId()); out.writeVarInt(p.sourceEntityId());
                    out.writeByte(p.type().ordinal());
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeVarInt(p.stateId()); out.writeInt(p.x()); out.writeShort(p.y()); out.writeInt(p.z());
                    out.writeByte(p.face()); out.writeByte(p.hitX()); out.writeByte(p.hitY()); out.writeByte(p.hitZ());
                }, in -> {
                    long actionId = in.readVarLong(); int source = in.readVarInt();
                    BlockActionEffectType type = enumValue(BlockActionEffectType.values(),
                            in.readUnsignedByte(), "block action effect");
                    try { return new CorePackets.BlockActionEffect(actionId, source, type,
                            in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readVarInt(),
                            in.readInt(), in.readUnsignedShort(), in.readInt(), in.readUnsignedByte(),
                            in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid block effect", e); }
                })));
        registry.register(type(5, CorePackets.EntityAction.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 32,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.request().actionId());
                    out.writeByte(p.request().action().ordinal());
                    out.writeVarInt(p.request().networkEntityId());
                }, in -> {
                    long id = in.readVarLong();
                    EntityActionRequest.Action action = enumValue(EntityActionRequest.Action.values(),
                            in.readUnsignedByte(), "entity action");
                    try { return new CorePackets.EntityAction(new EntityActionRequest(id, action, in.readVarInt())); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid entity action", e); }
                })));
        registry.register(type(46, CorePackets.EntityActionResult.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 4096,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.actionId()); out.writeBoolean(p.accepted());
                    out.writeString(p.message(), ProtocolLimits.MAX_MESSAGE_BYTES);
                }, in -> new CorePackets.EntityActionResult(in.readVarLong(), in.readBoolean(),
                        in.readString(ProtocolLimits.MAX_MESSAGE_BYTES)))));
        registry.register(type(2, CorePackets.InventoryAction.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 128 * 1024,
                PacketCodec.of((out, p) -> {
                    InventoryActionRequest request = p.request();
                    out.writeVarLong(request.transactionId()); out.writeVarInt(request.containerId());
                    out.writeVarInt(request.sourceSlot() + 1); out.writeVarInt(request.targetSlot() + 1);
                    out.writeByte(request.action().ordinal()); out.writeByte(request.button());
                    writeItemStack(out, request.offeredStack());
                }, in -> {
                    long id = in.readVarLong(); int container = in.readVarInt();
                    int source = in.readVarInt() - 1, target = in.readVarInt() - 1;
                    InventoryActionRequest.Action action = enumValue(InventoryActionRequest.Action.values(),
                            in.readUnsignedByte(), "inventory action");
                    try { return new CorePackets.InventoryAction(new InventoryActionRequest(id, container,
                            source, target, action, in.readUnsignedByte(), readItemStack(in))); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid inventory action", e); }
                })));
        registry.register(type(41, CorePackets.InventoryTransactionResult.class, PacketDirection.SERVER_TO_CLIENT,
                play, LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 4096,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.transactionId()); out.writeBoolean(p.accepted());
                    out.writeString(p.message(), ProtocolLimits.MAX_MESSAGE_BYTES);
                }, in -> new CorePackets.InventoryTransactionResult(in.readVarLong(), in.readBoolean(),
                        in.readString(ProtocolLimits.MAX_MESSAGE_BYTES)))));
        registry.register(type(42, CorePackets.InventorySlotUpdate.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 128 * 1024,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.containerId()); out.writeVarInt(p.revision()); out.writeVarInt(p.slot());
                    writeItemStack(out, p.stack());
                }, in -> new CorePackets.InventorySlotUpdate(in.readVarInt(), in.readVarInt(), in.readVarInt(),
                        readItemStack(in)))));
        registry.register(type(43, CorePackets.InventoryContent.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, ProtocolLimits.MAX_FRAME_BYTES - 32,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.containerId()); out.writeVarInt(p.revision());
                    if (p.stacks().size() > 4096) throw new ProtocolException("Inventory too large");
                    out.writeVarInt(p.stacks().size());
                    for (NetworkItemStack stack : p.stacks()) writeItemStack(out, stack);
                    writeItemStack(out, p.carried());
                }, in -> {
                    int container = in.readVarInt(), revision = in.readVarInt();
                    int count = checkedCount(in, 0, 4096, "inventory slots");
                    List<NetworkItemStack> stacks = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) stacks.add(readItemStack(in));
                    return new CorePackets.InventoryContent(container, revision, stacks, readItemStack(in));
                })));
        registry.register(type(47, CorePackets.ContainerOpen.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 512,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.containerId()); out.writeByte(p.kind().ordinal());
                    out.writeVarInt(p.containerSlots()); out.writeVarInt(p.rows());
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeInt(p.x()); out.writeShort(p.y()); out.writeInt(p.z());
                }, in -> {
                    int id = in.readVarInt();
                    ContainerKind kind = enumValue(ContainerKind.values(), in.readUnsignedByte(), "container kind");
                    try { return new CorePackets.ContainerOpen(id, kind, in.readVarInt(), in.readVarInt(),
                            in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES), in.readInt(),
                            in.readUnsignedShort(), in.readInt()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid container", e); }
                })));
        registry.register(type(48, CorePackets.ContainerClosed.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 8,
                PacketCodec.of((out, p) -> out.writeVarInt(p.containerId()), in -> {
                    try { return new CorePackets.ContainerClosed(in.readVarInt()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid container close", e); }
                })));
        registry.register(type(49, CorePackets.ContainerData.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 320,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.containerId());
                    int[] values = p.values(); out.writeVarInt(values.length);
                    for (int value : values) out.writeInt(value);
                }, in -> {
                    int id = in.readVarInt();
                    int count = checkedCount(in, 0, 64, "container properties");
                    int[] values = new int[count];
                    for (int i = 0; i < count; i++) values[i] = in.readInt();
                    try { return new CorePackets.ContainerData(id, values); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid container data", e); }
                })));
        registry.register(type(60, CorePackets.WorldSound.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.UNRELIABLE, 128,
                PacketCodec.of((out, p) -> {
                    out.writeString(p.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
                    out.writeByte(p.type().ordinal()); out.writeVarInt(p.data());
                    out.writeDouble(p.x()); out.writeDouble(p.y()); out.writeDouble(p.z());
                }, in -> new CorePackets.WorldSound(
                        in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES),
                        enumValue(WorldSoundType.values(), in.readUnsignedByte(), "world sound"),
                        in.readVarInt(), in.readDouble(), in.readDouble(), in.readDouble()))));
        registry.register(type(6, CorePackets.ContainerClose.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 8,
                PacketCodec.of((out, p) -> out.writeVarInt(p.containerId()), in -> {
                    try { return new CorePackets.ContainerClose(in.readVarInt()); }
                    catch (IllegalArgumentException e) { throw new ProtocolException("Invalid container close", e); }
                })));
        registry.register(type(7, CorePackets.ContainerOpenRequest.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 1,
                PacketCodec.of((out, p) -> { }, in -> new CorePackets.ContainerOpenRequest())));
        registry.register(type(8, CorePackets.RespawnRequest.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.GAMEPLAY, DeliveryClass.RELIABLE_ORDERED, 1,
                PacketCodec.of((out, p) -> { }, in -> new CorePackets.RespawnRequest())));
        registry.register(type(3, CorePackets.ChatMessageRequest.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.CHAT, DeliveryClass.RELIABLE_ORDERED, ProtocolLimits.MAX_CHAT_BYTES + 5,
                PacketCodec.of((out, p) -> out.writeString(p.message(), ProtocolLimits.MAX_CHAT_BYTES),
                        in -> new CorePackets.ChatMessageRequest(in.readString(ProtocolLimits.MAX_CHAT_BYTES)))));
        registry.register(type(44, CorePackets.ChatMessage.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.CHAT, DeliveryClass.RELIABLE_ORDERED, 2048,
                PacketCodec.of((out, p) -> {
                    out.writeUuid(p.sender()); out.writeString(p.senderName(), ProtocolLimits.MAX_USERNAME_BYTES);
                    out.writeVarLong(p.serverTick()); out.writeString(p.message(), ProtocolLimits.MAX_CHAT_BYTES);
                }, in -> new CorePackets.ChatMessage(in.readUuid(),
                        in.readString(ProtocolLimits.MAX_USERNAME_BYTES), in.readVarLong(),
                        in.readString(ProtocolLimits.MAX_CHAT_BYTES)))));
        registry.register(type(4, CorePackets.CommandRequest.class, PacketDirection.CLIENT_TO_SERVER, play,
                LogicalChannel.CHAT, DeliveryClass.RELIABLE_ORDERED, ProtocolLimits.MAX_COMMAND_BYTES + 16,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.commandId()); out.writeString(p.command(), ProtocolLimits.MAX_COMMAND_BYTES);
                }, in -> new CorePackets.CommandRequest(in.readVarLong(),
                        in.readString(ProtocolLimits.MAX_COMMAND_BYTES)))));
        registry.register(type(45, CorePackets.CommandResult.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.CHAT, DeliveryClass.RELIABLE_ORDERED, 64 * 1024,
                PacketCodec.of((out, p) -> {
                    out.writeVarLong(p.commandId()); out.writeBoolean(p.success());
                    writeStrings(out, p.messages(), 256, ProtocolLimits.MAX_MESSAGE_BYTES);
                }, in -> new CorePackets.CommandResult(in.readVarLong(), in.readBoolean(),
                        readStrings(in, 256, ProtocolLimits.MAX_MESSAGE_BYTES)))));
    }

    private static void registerEntities(PacketRegistry registry) {
        EnumSet<ConnectionState> play = states(ConnectionState.PLAY);
        registry.register(type(50, CorePackets.EntitySpawn.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.ENTITY, DeliveryClass.RELIABLE_ORDERED, 128 * 1024,
                PacketCodec.of((out, p) -> writeEntity(out, p.entity()),
                        in -> new CorePackets.EntitySpawn(readEntity(in)))));
        registry.register(type(51, CorePackets.EntityDespawn.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.ENTITY, DeliveryClass.RELIABLE_ORDERED, 16,
                PacketCodec.of((out, p) -> { out.writeVarInt(p.networkId()); out.writeVarInt(p.reason()); },
                        in -> new CorePackets.EntityDespawn(in.readVarInt(), in.readVarInt()))));
        registry.register(type(52, CorePackets.EntityState.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.ENTITY, DeliveryClass.UNRELIABLE_SEQUENCED, 128 * 1024,
                PacketCodec.of((out, p) -> { out.writeVarLong(p.serverTick()); writeEntity(out, p.entity()); },
                        in -> new CorePackets.EntityState(in.readVarLong(), readEntity(in)))));
        registry.register(type(53, CorePackets.EntityMetadata.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.ENTITY, DeliveryClass.RELIABLE_ORDERED, 128 * 1024,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.networkId()); out.writeVarLong(p.revision());
                    out.writeByteArray(p.metadata(), NetworkEntitySnapshot.MAX_METADATA_BYTES);
                }, in -> new CorePackets.EntityMetadata(in.readVarInt(), in.readVarLong(),
                        in.readByteArray(NetworkEntitySnapshot.MAX_METADATA_BYTES)))));
        registry.register(type(54, CorePackets.EntityEvent.class, PacketDirection.SERVER_TO_CLIENT, play,
                LogicalChannel.ENTITY, DeliveryClass.RELIABLE_ORDERED, 24,
                PacketCodec.of((out, p) -> {
                    out.writeVarInt(p.networkId()); out.writeVarInt(p.eventId()); out.writeVarInt(p.data());
                }, in -> new CorePackets.EntityEvent(in.readVarInt(), in.readVarInt(), in.readVarInt()))));
    }

    private static <P extends Packet> PacketType<P> type(int id, Class<P> packetClass,
            PacketDirection direction, EnumSet<ConnectionState> states, LogicalChannel channel,
            DeliveryClass delivery, int maxPayload, PacketCodec<P> codec) {
        return new PacketType<>(id, packetClass, direction, states, channel, delivery, maxPayload, codec);
    }

    private static EnumSet<ConnectionState> states(ConnectionState first, ConnectionState... rest) {
        return EnumSet.of(first, rest);
    }

    private static void writeStrings(PacketBuffer out, List<String> strings, int maxCount, int maxBytes)
            throws ProtocolException {
        if (strings.size() > maxCount) throw new ProtocolException("Too many strings: " + strings.size());
        out.writeVarInt(strings.size());
        for (String value : strings) out.writeString(value, maxBytes);
    }

    private static List<String> readStrings(PacketBuffer in, int maxCount, int maxBytes) throws ProtocolException {
        int count = in.readVarInt();
        if (count < 0 || count > maxCount) throw new ProtocolException("Invalid collection size " + count);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(in.readString(maxBytes));
        return List.copyOf(values);
    }

    private static void writePacks(PacketBuffer out, List<PackDescriptor> packs) throws ProtocolException {
        if (packs.size() > ProtocolLimits.MAX_PACKS) throw new ProtocolException("Too many packs");
        out.writeVarInt(packs.size());
        for (PackDescriptor pack : packs) {
            out.writeString(pack.id(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
            out.writeString(pack.version(), 128);
            out.writeRawBytes(pack.sha256());
            out.writeBoolean(pack.required());
            out.writeVarLong(pack.size());
            out.writeVarInt(pack.type().ordinal());
        }
    }

    private static List<PackDescriptor> readPacks(PacketBuffer in) throws ProtocolException {
        int count = in.readVarInt();
        if (count < 0 || count > ProtocolLimits.MAX_PACKS) throw new ProtocolException("Invalid pack count " + count);
        List<PackDescriptor> packs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES);
            String version = in.readString(128);
            byte[] hash = in.readRawBytes(32);
            boolean required = in.readBoolean();
            long size = in.readVarLong();
            int type = in.readVarInt();
            if (type < 0 || type >= PackDescriptor.PackType.values().length) throw new ProtocolException("Invalid pack type");
            packs.add(new PackDescriptor(id, version, hash, required, size, PackDescriptor.PackType.values()[type]));
        }
        return List.copyOf(packs);
    }

    private static DisconnectReason readDisconnectReason(PacketBuffer in) throws ProtocolException {
        int id = in.readVarInt();
        DisconnectReason[] values = DisconnectReason.values();
        if (id < 0 || id >= values.length) throw new ProtocolException("Invalid disconnect reason " + id);
        return values[id];
    }

    private static void writeChunk(PacketBuffer out, ChunkColumnSnapshot chunk) throws ProtocolException {
        out.writeString(chunk.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
        out.writeInt(chunk.chunkX()); out.writeInt(chunk.chunkZ()); out.writeVarLong(chunk.revision());
        out.writeVarInt(chunk.sections().size());
        for (ChunkSectionSnapshot section : chunk.sections()) {
            out.writeByte(section.sectionY());
            out.writeVarInt(section.nonAir());
            out.writeVarInt(section.paletteSize());
            for (int p = 0; p < section.paletteSize(); p++) {
                int stateId = section.paletteEntry(p);
                if (stateId < 0) throw new ProtocolException("Negative block state ID");
                out.writeVarInt(stateId);
            }
            out.writeByte(section.bitsPerEntry());
            out.writeVarInt(section.packedWordCount());
            for (int w = 0; w < section.packedWordCount(); w++) out.writeLong(section.packedWord(w));
            writeLight(out, section.skyLight());
            writeLight(out, section.blockLight());
        }
        for (int i = 0; i < ChunkColumnSnapshot.COLUMN_CELLS; i++) {
            int biome = chunk.biomeId(i);
            if (biome < 0 || biome > 1_000_000) throw new ProtocolException("Invalid biome ID");
            out.writeVarInt(biome);
        }
        for (int i = 0; i < ChunkColumnSnapshot.TINT_CORNERS; i++) {
            writeRgb24(out, chunk.grassTintCorner(i));
        }
        for (int i = 0; i < ChunkColumnSnapshot.TINT_CORNERS; i++) {
            writeRgb24(out, chunk.foliageTintCorner(i));
        }
        for (int i = 0; i < ChunkColumnSnapshot.COLUMN_CELLS; i++) {
            int height = chunk.height(i);
            if (height < 0 || height > 512) throw new ProtocolException("Invalid heightmap value");
            out.writeShort(height);
        }
        out.writeVarInt(chunk.blockEntities().size());
        for (BlockEntitySnapshot blockEntity : chunk.blockEntities()) {
            writeBlockEntity(out, blockEntity);
        }
    }

    /** Canonical payload used by bounded TCP chunk fragmentation. */
    public static byte[] encodeChunkSnapshot(ChunkColumnSnapshot chunk) throws ProtocolException {
        PacketBuffer output = new PacketBuffer();
        writeChunk(output, chunk);
        return output.toByteArray();
    }

    /** Reassembles only after all bounded fragments have arrived. */
    public static ChunkColumnSnapshot decodeChunkSnapshot(byte[] payload) throws ProtocolException {
        return decodeChunkSnapshot(payload, IntUnaryOperator.identity());
    }

    /** Decodes negotiated network block-state IDs directly into local runtime IDs. */
    public static ChunkColumnSnapshot decodeChunkSnapshot(byte[] payload,
                                                           IntUnaryOperator blockStateMapper)
            throws ProtocolException {
        PacketBuffer input = PacketBuffer.wrap(payload);
        ChunkColumnSnapshot chunk = readChunk(input, blockStateMapper);
        input.requireFullyRead();
        return chunk;
    }

    private static ChunkColumnSnapshot readChunk(PacketBuffer in) throws ProtocolException {
        return readChunk(in, IntUnaryOperator.identity());
    }

    private static ChunkColumnSnapshot readChunk(PacketBuffer in,
                                                 IntUnaryOperator blockStateMapper)
            throws ProtocolException {
        if (blockStateMapper == null) throw new NullPointerException("blockStateMapper");
        String dimension = in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES);
        int chunkX = in.readInt(), chunkZ = in.readInt();
        long revision = in.readVarLong();
        int sectionCount = checkedCount(in, 0, 16, "chunk sections");
        List<ChunkSectionSnapshot> sections = new ArrayList<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = in.readUnsignedByte();
            int nonAir = checkedCount(in, 1, ChunkSectionSnapshot.VOLUME, "non-air blocks");
            int paletteSize = checkedCount(in, 1, ChunkSectionSnapshot.VOLUME, "block palette");
            int[] palette = new int[paletteSize];
            for (int p = 0; p < paletteSize; p++) {
                int networkId = in.readVarInt();
                try {
                    palette[p] = blockStateMapper.applyAsInt(networkId);
                } catch (IllegalArgumentException invalid) {
                    throw new ProtocolException(invalid.getMessage() == null
                            ? "Invalid block-state mapping" : invalid.getMessage(), invalid);
                }
                if (palette[p] < 0) throw new ProtocolException("Negative mapped block state ID");
            }
            int bits = in.readUnsignedByte();
            int expectedLongs = bits == 0 ? 0
                    : (int) (((long) ChunkSectionSnapshot.VOLUME * bits + 63) / 64);
            int wordCount = checkedCount(in, 0, 7680, "packed palette words");
            if (wordCount != expectedLongs) throw new ProtocolException("Invalid packed palette word count");
            long[] words = new long[wordCount];
            for (int w = 0; w < wordCount; w++) words[w] = in.readLong();
            try {
                sections.add(new ChunkSectionSnapshot(sectionY, nonAir, palette, bits, words,
                        readLight(in), readLight(in)));
            } catch (IllegalArgumentException e) {
                throw new ProtocolException("Invalid chunk section", e);
            }
        }
        try {
            int[] biomes = new int[ChunkColumnSnapshot.COLUMN_CELLS];
            for (int i = 0; i < biomes.length; i++) {
                biomes[i] = checkedCount(in, 0, 1_000_000, "biome ID");
            }
            int[] grass = readRgb24Array(in, ChunkColumnSnapshot.TINT_CORNERS);
            int[] foliage = readRgb24Array(in, ChunkColumnSnapshot.TINT_CORNERS);
            int[] heightmap = new int[ChunkColumnSnapshot.COLUMN_CELLS];
            for (int i = 0; i < heightmap.length; i++) {
                heightmap[i] = in.readUnsignedShort();
                if (heightmap[i] > 512) throw new ProtocolException("Invalid heightmap value");
            }
            int blockEntityCount = checkedCount(in, 0, ChunkSectionSnapshot.VOLUME * 16,
                    "block entities");
            List<BlockEntitySnapshot> blockEntities = new ArrayList<>(blockEntityCount);
            for (int i = 0; i < blockEntityCount; i++) {
                blockEntities.add(readBlockEntity(in));
            }
            return new ChunkColumnSnapshot(dimension, chunkX, chunkZ, revision, sections,
                    biomes, grass, foliage, heightmap, blockEntities);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid chunk column", e);
        }
    }

    private static void writeBlockEntity(PacketBuffer out, BlockEntitySnapshot blockEntity)
            throws ProtocolException {
        out.writeByte(blockEntity.localX());
        out.writeVarInt(blockEntity.y());
        out.writeByte(blockEntity.localZ());
        out.writeString(blockEntity.typeId(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
        byte[] data = blockEntity.dataView();
        out.writeVarInt(data.length);
        out.writeRawBytes(data);
    }

    private static BlockEntitySnapshot readBlockEntity(PacketBuffer in) throws ProtocolException {
        int localX = in.readUnsignedByte();
        int y = in.readVarInt();
        int localZ = in.readUnsignedByte();
        String typeId = in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES);
        int dataLength = checkedCount(in, 0, BlockEntitySnapshot.MAX_DATA_BYTES,
                "block entity data");
        try {
            return new BlockEntitySnapshot(localX, y, localZ, typeId, in.readRawBytes(dataLength));
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException("Invalid block entity", invalid);
        }
    }

    private static void writeLight(PacketBuffer out, LightPlane light) {
        out.writeByte(light.mode().ordinal());
        out.writeRawBytes(light.packedNibblesView());
    }

    private static LightPlane readLight(PacketBuffer in) throws ProtocolException {
        int modeId = in.readUnsignedByte();
        if (modeId >= LightPlane.Mode.values().length) throw new ProtocolException("Invalid light mode");
        LightPlane.Mode mode = LightPlane.Mode.values()[modeId];
        int bytes = mode == LightPlane.Mode.PACKED_NIBBLES ? LightPlane.PACKED_BYTES : 0;
        return new LightPlane(mode, in.readRawBytes(bytes));
    }

    private static void writeRgb24(PacketBuffer out, int value) throws ProtocolException {
        if ((value & 0xFF000000) != 0) throw new ProtocolException("Invalid RGB tint");
        out.writeByte(value >>> 16);
        out.writeByte(value >>> 8);
        out.writeByte(value);
    }

    private static int[] readRgb24Array(PacketBuffer in, int count) throws ProtocolException {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8
                    | in.readUnsignedByte();
        }
        return values;
    }

    private static void writeChunkUpdateHeader(PacketBuffer out, String dimension, int chunkX, int chunkZ,
                                               long revision) throws ProtocolException {
        out.writeString(dimension, ProtocolLimits.MAX_IDENTIFIER_BYTES);
        out.writeInt(chunkX); out.writeInt(chunkZ); out.writeVarLong(revision);
    }

    private static void writeBlockChange(PacketBuffer out, BlockChange change) {
        out.writeVarInt(change.packedPosition());
        out.writeVarInt(change.stateId());
    }

    private static BlockChange readBlockChange(PacketBuffer in) throws ProtocolException {
        int packed = in.readVarInt();
        int stateId = in.readVarInt();
        try { return BlockChange.fromPacked(packed, stateId); }
        catch (IllegalArgumentException e) { throw new ProtocolException("Invalid block change", e); }
    }

    private static int checkedCount(PacketBuffer in, int minimum, int maximum, String name)
            throws ProtocolException {
        int count = in.readVarInt();
        if (count < minimum || count > maximum) throw new ProtocolException("Invalid " + name + " count " + count);
        return count;
    }

    private static void writePlayerState(PacketBuffer out, PlayerStateSnapshot state) throws ProtocolException {
        out.writeVarLong(state.serverTick()); out.writeVarLong(state.lastProcessedInputSequence());
        out.writeString(state.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES);
        out.writeDouble(state.x()); out.writeDouble(state.y()); out.writeDouble(state.z());
        out.writeFloat((float) state.velocityX()); out.writeFloat((float) state.velocityY());
        out.writeFloat((float) state.velocityZ()); out.writeFloat(state.yaw()); out.writeFloat(state.pitch());
        out.writeBoolean(state.grounded()); out.writeVarInt(state.gameMode().ordinal());
        out.writeVarInt(state.movementState());
        out.writeFloat(state.health()); out.writeByte(state.foodLevel());
        out.writeFloat(state.saturation()); out.writeByte(state.selectedHotbarSlot());
        out.writeVarInt(state.vehicleEntityId());
        out.writeFloat(state.spectatorFlySpeed());
    }

    private static PlayerStateSnapshot readPlayerState(PacketBuffer in) throws ProtocolException {
        long serverTick = in.readVarLong(), sequence = in.readVarLong();
        String dimension = in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES);
        double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
        double velocityX = in.readFloat(), velocityY = in.readFloat(), velocityZ = in.readFloat();
        float yaw = in.readFloat(), pitch = in.readFloat();
        boolean grounded = in.readBoolean();
        de.skyengine.shared.player.PlayerGameMode gameMode = enumValue(
                de.skyengine.shared.player.PlayerGameMode.values(), in.readVarInt(), "player game mode");
        int movementState = in.readVarInt();
        float health = in.readFloat(); int foodLevel = in.readUnsignedByte();
        float saturation = in.readFloat(); int selectedHotbarSlot = in.readUnsignedByte();
        int vehicleEntityId = in.readVarInt();
        float spectatorFlySpeed = in.readFloat();
        try {
            return new PlayerStateSnapshot(serverTick, sequence, dimension, x, y, z,
                    velocityX, velocityY, velocityZ, yaw, pitch, grounded, gameMode, movementState,
                    health, foodLevel, saturation, selectedHotbarSlot, vehicleEntityId,
                    spectatorFlySpeed);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid player state", e);
        }
    }

    private static void writeItemStack(PacketBuffer out, NetworkItemStack stack) throws ProtocolException {
        out.writeVarInt(stack.itemId()); out.writeVarInt(stack.count());
        out.writeByteArray(stack.components(), NetworkItemStack.MAX_COMPONENT_BYTES);
    }

    private static NetworkItemStack readItemStack(PacketBuffer in) throws ProtocolException {
        try {
            return new NetworkItemStack(in.readVarInt(), in.readVarInt(),
                    in.readByteArray(NetworkItemStack.MAX_COMPONENT_BYTES));
        } catch (IllegalArgumentException e) { throw new ProtocolException("Invalid item stack", e); }
    }

    private static <E> E enumValue(E[] values, int id, String name) throws ProtocolException {
        if (id < 0 || id >= values.length) throw new ProtocolException("Invalid " + name + " " + id);
        return values[id];
    }

    private static void writeEntity(PacketBuffer out, NetworkEntitySnapshot entity) throws ProtocolException {
        out.writeVarInt(entity.networkId()); out.writeVarInt(entity.typeId());
        out.writeString(entity.dimension(), ProtocolLimits.MAX_IDENTIFIER_BYTES); out.writeVarLong(entity.revision());
        out.writeDouble(entity.x()); out.writeDouble(entity.y()); out.writeDouble(entity.z());
        out.writeFloat((float) entity.velocityX()); out.writeFloat((float) entity.velocityY());
        out.writeFloat((float) entity.velocityZ()); out.writeFloat(entity.yaw()); out.writeFloat(entity.pitch());
        out.writeByteArray(entity.metadata(), NetworkEntitySnapshot.MAX_METADATA_BYTES);
    }

    private static NetworkEntitySnapshot readEntity(PacketBuffer in) throws ProtocolException {
        int networkId = in.readVarInt(), typeId = in.readVarInt();
        String dimension = in.readString(ProtocolLimits.MAX_IDENTIFIER_BYTES);
        long revision = in.readVarLong();
        double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
        double vx = in.readFloat(), vy = in.readFloat(), vz = in.readFloat();
        float yaw = in.readFloat(), pitch = in.readFloat();
        byte[] metadata = in.readByteArray(NetworkEntitySnapshot.MAX_METADATA_BYTES);
        try { return new NetworkEntitySnapshot(networkId, typeId, dimension, revision,
                x, y, z, vx, vy, vz, yaw, pitch, metadata); }
        catch (IllegalArgumentException e) { throw new ProtocolException("Invalid entity snapshot", e); }
    }

    private CoreProtocol() {}
}
