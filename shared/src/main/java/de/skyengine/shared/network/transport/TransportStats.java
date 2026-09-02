package de.skyengine.shared.network.transport;

public record TransportStats(long receivedPackets, long receivedBytes, long sentPackets, long sentBytes,
                             int inboundQueue, int outboundQueue) {}
