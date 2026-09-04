package de.skyengine.shared.network.transport;

import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.ConnectionStateMachine;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded in-process transport used by the integrated server. */
public final class LocalTransport {
    public static final int DEFAULT_CAPACITY = 4096;

    public record Pair(TransportConnection client, TransportConnection server) {}

    public static Pair create() { return create(DEFAULT_CAPACITY); }

    public static Pair create(int capacity) {
        if (capacity < 16) throw new IllegalArgumentException("Local transport capacity too small");
        ArrayBlockingQueue<PacketEnvelope> clientToServer = new ArrayBlockingQueue<>(capacity);
        ArrayBlockingQueue<PacketEnvelope> serverToClient = new ArrayBlockingQueue<>(capacity);
        AtomicBoolean open = new AtomicBoolean(true);
        Endpoint client = new Endpoint("local-client-" + UUID.randomUUID(), serverToClient, clientToServer, open);
        Endpoint server = new Endpoint("local-server-" + UUID.randomUUID(), clientToServer, serverToClient, open);
        return new Pair(client, server);
    }

    private static final class Endpoint implements TransportConnection {
        private static final SocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
        private final String id;
        private final ArrayBlockingQueue<PacketEnvelope> inbound;
        private final ArrayBlockingQueue<PacketEnvelope> outbound;
        private final AtomicBoolean open;
        private final ConnectionStateMachine state = new ConnectionStateMachine();
        private final AtomicLong receivedPackets = new AtomicLong();
        private final AtomicLong sentPackets = new AtomicLong();

        private Endpoint(String id, ArrayBlockingQueue<PacketEnvelope> inbound,
                         ArrayBlockingQueue<PacketEnvelope> outbound, AtomicBoolean open) {
            this.id = id;
            this.inbound = inbound;
            this.outbound = outbound;
            this.open = open;
        }

        @Override public String id() { return this.id; }
        @Override public SocketAddress remoteAddress() { return LOCAL; }
        @Override public ConnectionState state() { return this.state.state(); }
        @Override public void transitionState(ConnectionState expected, ConnectionState next) {
            this.state.transition(expected, next);
        }
        @Override public boolean send(PacketEnvelope packet) {
            Objects.requireNonNull(packet);
            boolean sent = this.open.get() && this.outbound.offer(packet);
            if (sent) this.sentPackets.incrementAndGet();
            return sent;
        }
        @Override public PacketEnvelope pollInbound() {
            PacketEnvelope packet = this.inbound.poll();
            if (packet != null) this.receivedPackets.incrementAndGet();
            return packet;
        }
        @Override public int inboundSize() { return this.inbound.size(); }
        @Override public int outboundSize() { return this.outbound.size(); }
        @Override public boolean open() { return this.open.get(); }
        @Override public TransportStats stats() {
            return new TransportStats(this.receivedPackets.get(), 0, this.sentPackets.get(), 0,
                    inboundSize(), outboundSize());
        }
        @Override public void disconnect(DisconnectReason reason, String message) {
            if (this.open.compareAndSet(true, false)) {
                this.outbound.offer(new PacketEnvelope(new CorePackets.Disconnect(reason,
                        message == null ? "Connection closed" : message)));
            }
            this.state.close();
        }
        @Override public void close() {
            this.open.set(false);
            this.state.close();
        }
    }

    private LocalTransport() {}
}
