package de.skyengine.client.network;

import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.ProtocolLimits;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.server.network.NettyTransportConnection;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** Concurrent one-shot application-level status probes used by the multiplayer server browser. */
public final class ServerStatusPinger {
    public enum State { CHECKING, ONLINE, INCOMPATIBLE, OFFLINE }

    public record Result(State state, long latencyMillis, int onlinePlayers, int maxPlayers,
                         String motd, String detail) {
        public static Result checking() { return new Result(State.CHECKING, -1, 0, 0, "", ""); }
    }

    private static final long RESPONSE_TIMEOUT_NANOS = 3_000_000_000L;
    private final Map<String, Result> results = new ConcurrentHashMap<>();
    private final Semaphore concurrency = new Semaphore(16);
    private final AtomicLong generation = new AtomicLong();

    public void refresh(Collection<String> addresses) {
        long refreshGeneration = this.generation.incrementAndGet();
        for (String text : addresses) {
            this.results.put(text, Result.checking());
            Thread.ofVirtual().name("Server Status " + text).start(() -> probe(text, refreshGeneration));
        }
    }

    public Result result(String address) {
        return this.results.getOrDefault(address, Result.checking());
    }

    private void probe(String text, long refreshGeneration) {
        boolean acquired = false;
        try {
            this.concurrency.acquire();
            acquired = true;
            ServerAddress address = ServerAddress.parse(text);
            try (NettyClientTransport transport = new NettyClientTransport(CoreProtocol.createRegistry(),
                    ProtocolLimits.MAX_FRAME_BYTES, 2_500)) {
                NettyTransportConnection connection = transport.connect(address.socketAddress());
                long started = System.nanoTime();
                long nonce = started ^ Thread.currentThread().threadId();
                if (!connection.send(new PacketEnvelope(
                        new CorePackets.ServerStatusRequest(nonce, EngineInfo.PROTOCOL_VERSION)))) {
                    throw new IllegalStateException("Status request queue is full");
                }
                connection.flushOutbound(4096);
                long deadline = started + RESPONSE_TIMEOUT_NANOS;
                while (System.nanoTime() < deadline && connection.open()) {
                    PacketEnvelope envelope = connection.pollInbound();
                    if (envelope == null) {
                        Thread.sleep(2);
                        continue;
                    }
                    if (!(envelope.packet() instanceof CorePackets.ServerStatusResponse response)
                            || response.nonce() != nonce) {
                        throw new IllegalStateException("Invalid status response");
                    }
                    long received = connection.lastPacketReceivedNanos();
                    long latency = address.socketAddress().getAddress().isLoopbackAddress() ? 0
                            : Math.max(0, ((received == 0 ? System.nanoTime() : received) - started)
                            / 1_000_000L);
                    State state = response.protocolVersion() == EngineInfo.PROTOCOL_VERSION
                            ? State.ONLINE : State.INCOMPATIBLE;
                    publish(text, refreshGeneration, new Result(state, latency, response.onlinePlayers(),
                            response.maxPlayers(), response.motd(), response.engineVersion()));
                    return;
                }
                throw new IllegalStateException("Status request timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            publish(text, refreshGeneration, new Result(State.OFFLINE, -1, 0, 0, "", "Cancelled"));
        } catch (RuntimeException error) {
            String detail = error.getMessage();
            publish(text, refreshGeneration, new Result(State.OFFLINE, -1, 0, 0, "",
                    detail == null ? error.getClass().getSimpleName() : detail));
        } finally {
            if (acquired) this.concurrency.release();
        }
    }

    private void publish(String address, long refreshGeneration, Result result) {
        if (this.generation.get() == refreshGeneration) this.results.put(address, result);
    }
}
