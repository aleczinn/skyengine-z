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
        Mailbox clientToServer = new Mailbox(capacity);
        Mailbox serverToClient = new Mailbox(capacity);
        AtomicBoolean open = new AtomicBoolean(true);
        Endpoint client = new Endpoint("local-client-" + UUID.randomUUID(), serverToClient, clientToServer, open);
        Endpoint server = new Endpoint("local-server-" + UUID.randomUUID(), clientToServer, serverToClient, open);
        return new Pair(client, server);
    }

    /**
     * Bulk chunk traffic may consume only its own bounded queue. Control/gameplay therefore
     * keeps guaranteed headroom even while the integrated client is behind on installation.
     */
    private static final class Mailbox {
        private final int capacity;
        private final int bulkLimit;
        private final ArrayBlockingQueue<PacketEnvelope> priority;
        private final ArrayBlockingQueue<PacketEnvelope> bulk;

        private Mailbox(int capacity) {
            int reservedPrioritySlots = Math.max(8, capacity / 8);
            this.capacity = capacity;
            this.bulkLimit = capacity - reservedPrioritySlots;
            this.priority = new ArrayBlockingQueue<>(capacity);
            this.bulk = new ArrayBlockingQueue<>(this.bulkLimit);
        }

        synchronized boolean offer(PacketEnvelope packet) {
            if (isBulkChunkPacket(packet)) {
                return this.bulk.size() < this.bulkLimit && size() < this.capacity && this.bulk.offer(packet);
            }
            return size() < this.capacity && this.priority.offer(packet);
        }

        synchronized boolean offerBulkBatch(java.util.List<PacketEnvelope> packets) {
            if (this.bulk.size() + packets.size() > this.bulkLimit
                    || size() + packets.size() > this.capacity) return false;
            this.bulk.addAll(packets);
            return true;
        }

        synchronized PacketEnvelope poll() {
            PacketEnvelope packet = this.priority.poll();
            return packet != null ? packet : this.bulk.poll();
        }

        synchronized int size() { return this.priority.size() + this.bulk.size(); }

        synchronized int cancelBatch(long batchId) {
            boolean startQueued = this.bulk.stream().anyMatch(envelope ->
                    envelope.packet() instanceof CorePackets.ChunkBatchStart start
                            && start.batchId() == batchId);
            if (!startQueued) return 0;
            int before = this.bulk.size();
            this.bulk.removeIf(envelope -> packetBatchId(envelope.packet()) == batchId);
            return before - this.bulk.size();
        }
    }

    private static final class Endpoint implements TransportConnection, BatchTransport {
        private static final SocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
        private final String id;
        private final Mailbox inbound;
        private final Mailbox outbound;
        private final AtomicBoolean open;
        private final ConnectionStateMachine state = new ConnectionStateMachine();
        private final AtomicLong receivedPackets = new AtomicLong();
        private final AtomicLong sentPackets = new AtomicLong();

        private Endpoint(String id, Mailbox inbound, Mailbox outbound, AtomicBoolean open) {
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
        @Override public boolean transfersImmutableObjects() { return true; }
        @Override public boolean sendBatch(java.util.List<PacketEnvelope> packets) {
            Objects.requireNonNull(packets);
            if (!this.open.get() || packets.isEmpty()) return false;
            for (PacketEnvelope packet : packets) {
                Objects.requireNonNull(packet);
                if (!isBulkChunkPacket(packet)) return false;
            }
            if (!this.outbound.offerBulkBatch(packets)) return false;
            this.sentPackets.addAndGet(packets.size());
            return true;
        }
        @Override public boolean cancelBatch(long batchId) {
            int removed = this.outbound.cancelBatch(batchId);
            if (removed > 0) this.sentPackets.addAndGet(-removed);
            return removed > 0;
        }
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

    private static boolean isBulkChunkPacket(PacketEnvelope envelope) {
        return packetBatchId(envelope.packet()) >= 0;
    }

    private static long packetBatchId(de.skyengine.shared.network.Packet packet) {
        if (packet instanceof CorePackets.ChunkBatchStart value) return value.batchId();
        if (packet instanceof CorePackets.ChunkColumnData value) return value.batchId();
        if (packet instanceof CorePackets.ChunkColumnFragment value) return value.batchId();
        if (packet instanceof CorePackets.ChunkBatchEnd value) return value.batchId();
        return -1;
    }

    private LocalTransport() {}
}
