package de.skyengine.shared.network;

import java.util.Objects;
import java.util.Set;

public record PacketType<P extends Packet>(
        int id,
        Class<P> packetClass,
        PacketDirection direction,
        Set<ConnectionState> states,
        LogicalChannel channel,
        DeliveryClass delivery,
        int maximumPayload,
        PacketCodec<P> codec) {
    public PacketType {
        if (id < 0) throw new IllegalArgumentException("Negative packet id");
        Objects.requireNonNull(packetClass);
        Objects.requireNonNull(direction);
        states = Set.copyOf(states);
        if (states.isEmpty()) throw new IllegalArgumentException("Packet needs an allowed state");
        Objects.requireNonNull(channel);
        Objects.requireNonNull(delivery);
        if (maximumPayload < 0 || maximumPayload > ProtocolLimits.MAX_DECOMPRESSED_BYTES) {
            throw new IllegalArgumentException("Invalid payload limit");
        }
        Objects.requireNonNull(codec);
    }

    public boolean sequenced() {
        return this.delivery == DeliveryClass.UNRELIABLE_SEQUENCED;
    }
}
