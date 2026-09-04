package de.skyengine.shared.network;

import java.util.Objects;

public record PacketEnvelope(Packet packet, long sequence) {
    public PacketEnvelope {
        Objects.requireNonNull(packet);
        if (sequence < 0) throw new IllegalArgumentException("Negative sequence");
    }

    public PacketEnvelope(Packet packet) { this(packet, 0); }
}
