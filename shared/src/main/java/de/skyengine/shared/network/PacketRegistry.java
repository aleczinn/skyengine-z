package de.skyengine.shared.network;

import java.util.HashMap;
import java.util.Map;

/** Immutable-after-bootstrap packet registry keyed by direction, state and packet id. */
public final class PacketRegistry {
    private record DecodeKey(PacketDirection direction, ConnectionState state, int id) {}

    private final Map<DecodeKey, PacketType<?>> decoders = new HashMap<>();
    private final Map<Class<?>, PacketType<?>> encoders = new HashMap<>();
    private boolean frozen;

    public synchronized <P extends Packet> PacketType<P> register(PacketType<P> type) {
        if (this.frozen) throw new IllegalStateException("Packet registry is frozen");
        for (ConnectionState state : type.states()) {
            DecodeKey key = new DecodeKey(type.direction(), state, type.id());
            if (this.decoders.putIfAbsent(key, type) != null) {
                throw new IllegalStateException("Duplicate packet id " + type.id() + " for " + type.direction() + "/" + state);
            }
        }
        PacketType<?> previous = this.encoders.putIfAbsent(type.packetClass(), type);
        if (previous != null) throw new IllegalStateException("Duplicate packet class " + type.packetClass().getName());
        return type;
    }

    public synchronized void freeze() { this.frozen = true; }
    public synchronized boolean frozen() { return this.frozen; }

    public PacketType<?> type(Packet packet) throws ProtocolException {
        PacketType<?> type = this.encoders.get(packet.getClass());
        if (type == null) throw new ProtocolException("Unregistered packet " + packet.getClass().getName());
        return type;
    }

    public PacketType<?> type(PacketDirection direction, ConnectionState state, int id) throws ProtocolException {
        PacketType<?> type = this.decoders.get(new DecodeKey(direction, state, id));
        if (type == null) throw new ProtocolException("Unknown packet id " + id + " for " + direction + "/" + state);
        return type;
    }

    @SuppressWarnings("unchecked")
    private static <P extends Packet> void encodeUnchecked(PacketType<?> type, PacketBuffer out, Packet packet)
            throws ProtocolException {
        PacketType<P> typed = (PacketType<P>) type;
        typed.codec().encode(out, typed.packetClass().cast(packet));
    }

    public byte[] encode(PacketDirection direction, ConnectionState state, PacketEnvelope envelope)
            throws ProtocolException {
        PacketType<?> type = type(envelope.packet());
        if (type.direction() != direction || !type.states().contains(state)) {
            throw new ProtocolException("Packet " + type.packetClass().getSimpleName() + " is not allowed in " + direction + "/" + state);
        }
        PacketBuffer payload = new PacketBuffer();
        encodeUnchecked(type, payload, envelope.packet());
        byte[] payloadBytes = payload.toByteArray();
        if (payloadBytes.length > type.maximumPayload()) throw new ProtocolException("Packet payload exceeds declared limit");

        PacketBuffer body = new PacketBuffer(payloadBytes.length + 24);
        body.writeByte(type.channel().ordinal());
        body.writeByte(type.sequenced() ? 1 : 0);
        body.writeVarInt(type.id());
        if (type.sequenced()) body.writeVarLong(envelope.sequence());
        body.writeRawBytes(payloadBytes);
        return body.toByteArray();
    }

    public DecodedPacket decode(PacketDirection direction, ConnectionState state, byte[] body)
            throws ProtocolException {
        PacketBuffer input = PacketBuffer.wrap(body);
        LogicalChannel channel = LogicalChannel.fromId(input.readUnsignedByte());
        int flags = input.readUnsignedByte();
        if ((flags & ~1) != 0) throw new ProtocolException("Unknown packet flags " + flags);
        int id = input.readVarInt();
        PacketType<?> type = type(direction, state, id);
        if (type.channel() != channel) throw new ProtocolException("Packet sent on wrong channel");
        boolean sequenced = (flags & 1) != 0;
        if (sequenced != type.sequenced()) throw new ProtocolException("Invalid sequence flag");
        long sequence = sequenced ? input.readVarLong() : 0;
        if (input.readableBytes() > type.maximumPayload()) throw new ProtocolException("Packet payload exceeds declared limit");
        Packet packet = type.codec().decode(input);
        input.requireFullyRead();
        return new DecodedPacket(type, packet, sequence);
    }
}
