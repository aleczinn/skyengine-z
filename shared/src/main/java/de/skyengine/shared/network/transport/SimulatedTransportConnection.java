package de.skyengine.shared.network.transport;

import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.DeliveryClass;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.PacketDirection;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.PacketRegistry;
import de.skyengine.shared.network.ProtocolException;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.LongSupplier;

/** Deterministic debug wrapper for latency/jitter/bandwidth and unreliable-packet faults. */
public final class SimulatedTransportConnection implements TransportConnection, BatchTransport {
    private static final int MAX_DELAYED_PACKETS = 4096;
    private record Delayed(long releaseNanos, PacketEnvelope envelope) {}

    private final TransportConnection delegate;
    private final PacketRegistry registry;
    private final PacketDirection outboundDirection;
    private final NetworkSimulationConfig config;
    private final LongSupplier clock;
    private final Random random;
    private final List<Delayed> outgoing = new ArrayList<>();
    private final List<Delayed> incoming = new ArrayList<>();
    private long nextBandwidthNanos;
    private long nextReliableOutgoingNanos;
    private long nextReliableIncomingNanos;

    public SimulatedTransportConnection(TransportConnection delegate, PacketRegistry registry,
                                        PacketDirection outboundDirection, NetworkSimulationConfig config) {
        this(delegate, registry, outboundDirection, config, System::nanoTime);
    }

    SimulatedTransportConnection(TransportConnection delegate, PacketRegistry registry,
                                 PacketDirection outboundDirection, NetworkSimulationConfig config,
                                 LongSupplier clock) {
        this.delegate = Objects.requireNonNull(delegate); this.registry = Objects.requireNonNull(registry);
        this.outboundDirection = Objects.requireNonNull(outboundDirection); this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock); this.random = new Random(config.randomSeed());
    }

    @Override public String id() { return "simulated-" + this.delegate.id(); }
    @Override public SocketAddress remoteAddress() { return this.delegate.remoteAddress(); }
    @Override public ConnectionState state() { return this.delegate.state(); }
    @Override public void transitionState(ConnectionState expected, ConnectionState next) {
        this.delegate.transitionState(expected, next);
    }

    @Override public boolean send(PacketEnvelope packet) {
        pump();
        if (!open() || this.outgoing.size() >= MAX_DELAYED_PACKETS) return false;
        DeliveryClass delivery = delivery(packet);
        if (unreliable(delivery) && this.random.nextDouble() < this.config.packetLoss()) return true;
        long release = releaseTime(packet, this.clock.getAsLong());
        this.outgoing.add(new Delayed(release, packet));
        if (unreliable(delivery) && this.random.nextDouble() < this.config.duplication()
                && this.outgoing.size() < MAX_DELAYED_PACKETS) {
            this.outgoing.add(new Delayed(release + 1, packet));
        }
        return true;
    }

    @Override public synchronized boolean sendBatch(List<PacketEnvelope> packets) {
        Objects.requireNonNull(packets);
        pump();
        if (!open() || packets.isEmpty() || this.outgoing.size() > MAX_DELAYED_PACKETS - packets.size()) {
            return false;
        }
        for (PacketEnvelope packet : packets) Objects.requireNonNull(packet);
        // Chunk traffic is reliable/ordered. Reserving the complete delayed batch here avoids
        // the partial-admission bug that can otherwise strand a server snapshot lease.
        for (PacketEnvelope packet : packets) {
            long release = releaseTime(packet, this.clock.getAsLong());
            this.outgoing.add(new Delayed(release, packet));
        }
        return true;
    }

    @Override public PacketEnvelope pollInbound() {
        pump();
        long now = this.clock.getAsLong();
        for (int i = 0; i < this.incoming.size(); i++) {
            Delayed delayed = this.incoming.get(i);
            if (delayed.releaseNanos() <= now) {
                this.incoming.remove(i);
                return delayed.envelope();
            }
        }
        return null;
    }

    public void pump() {
        long now = this.clock.getAsLong();
        this.outgoing.sort(Comparator.comparingLong(Delayed::releaseNanos));
        while (!this.outgoing.isEmpty() && this.outgoing.getFirst().releaseNanos() <= now) {
            Delayed delayed = this.outgoing.getFirst();
            if (!this.delegate.send(delayed.envelope())) break;
            this.outgoing.removeFirst();
        }
        PacketEnvelope inbound;
        while (this.incoming.size() < MAX_DELAYED_PACKETS && (inbound = this.delegate.pollInbound()) != null) {
            DeliveryClass delivery = delivery(inbound);
            if (unreliable(delivery) && this.random.nextDouble() < this.config.packetLoss()) continue;
            long release = baseRelease(now, delivery);
            if (!unreliable(delivery)) {
                release = Math.max(release, this.nextReliableIncomingNanos);
                this.nextReliableIncomingNanos = release;
            }
            this.incoming.add(new Delayed(release, inbound));
            if (unreliable(delivery) && this.random.nextDouble() < this.config.duplication()
                    && this.incoming.size() < MAX_DELAYED_PACKETS) this.incoming.add(new Delayed(release + 1, inbound));
        }
    }

    @Override public int inboundSize() { return this.incoming.size() + this.delegate.inboundSize(); }
    @Override public int outboundSize() { return this.outgoing.size() + this.delegate.outboundSize(); }
    @Override public boolean open() { return this.delegate.open(); }
    @Override public TransportStats stats() { return this.delegate.stats(); }
    @Override public void disconnect(DisconnectReason reason, String message) { this.delegate.disconnect(reason, message); }
    @Override public void close() { this.outgoing.clear(); this.incoming.clear(); this.delegate.close(); }

    private long releaseTime(PacketEnvelope packet, long now) {
        long release = baseRelease(now, delivery(packet));
        if (!unreliable(delivery(packet))) {
            release = Math.max(release, this.nextReliableOutgoingNanos);
            this.nextReliableOutgoingNanos = release;
        }
        if (this.config.bytesPerSecond() > 0) {
            int bytes;
            try { bytes = this.registry.encode(this.outboundDirection, state(), packet).length + 5; }
            catch (ProtocolException e) { bytes = 128; }
            long serialization = Math.max(1, (long) Math.ceil(bytes * 1_000_000_000.0
                    / this.config.bytesPerSecond()));
            release = Math.max(release, this.nextBandwidthNanos) + serialization;
            this.nextBandwidthNanos = release;
        }
        return release;
    }

    private long baseRelease(long now, DeliveryClass delivery) {
        long jitter = this.config.jitterMillis() == 0 ? 0
                : Math.round((this.random.nextDouble() * 2 - 1) * this.config.jitterMillis() * 1_000_000.0);
        long release = now + this.config.latencyMillis() * 1_000_000L + jitter;
        if (unreliable(delivery) && this.random.nextDouble() < this.config.reordering()) {
            release += this.config.jitterMillis() * 2_000_000L;
        }
        return Math.max(now, release);
    }

    private DeliveryClass delivery(PacketEnvelope packet) {
        try { return this.registry.type(packet.packet()).delivery(); }
        catch (ProtocolException e) { return DeliveryClass.RELIABLE_ORDERED; }
    }

    private static boolean unreliable(DeliveryClass delivery) {
        return delivery == DeliveryClass.UNRELIABLE || delivery == DeliveryClass.UNRELIABLE_SEQUENCED;
    }
}
