package de.skyengine.shared.network;

public record DecodedPacket(PacketType<?> type, Packet packet, long sequence) {}
