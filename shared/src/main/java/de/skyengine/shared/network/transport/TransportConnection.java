package de.skyengine.shared.network.transport;

import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.PacketEnvelope;

import java.net.SocketAddress;
import java.time.Duration;

public interface TransportConnection extends AutoCloseable {
    String id();
    SocketAddress remoteAddress();
    ConnectionState state();
    void transitionState(ConnectionState expected, ConnectionState next);
    boolean send(PacketEnvelope packet);
    PacketEnvelope pollInbound();
    int inboundSize();
    int outboundSize();
    default TransportStats stats() {
        return new TransportStats(0, 0, 0, 0, inboundSize(), outboundSize());
    }
    boolean open();
    void disconnect(DisconnectReason reason, String message);
    @Override void close();
}
