package de.skyengine.tools.network;

import de.skyengine.server.ServerApplication;
import de.skyengine.server.ServerConfig;
import de.skyengine.server.world.HeadlessWorldRuntime;
import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.Packet;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.player.PlayerInputFrame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Headless local-bot smoke/load harness for multiplayer sessions without rendering dependencies. */
public final class MultiplayerLoadHarness {
    public static void main(String[] args) throws Exception {
        int players = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        if (players < 1 || players > 1000 || seconds < 1 || seconds > 3600) {
            throw new IllegalArgumentException("Usage: MultiplayerLoadHarness [players 1..1000] [seconds 1..3600]");
        }
        Path root = Files.createTempDirectory("skyengine-load-");
        List<Bot> bots = new ArrayList<>(players);
        try {
            ServerConfig defaults = ServerConfig.load(root);
            ServerConfig config = withMaxPlayers(defaults, Math.max(players, defaults.maxPlayers()));
            try (HeadlessWorldRuntime world = new HeadlessWorldRuntime(config.worldDirectory());
                 ServerApplication server = new ServerApplication(config, world)) {
                bots.add(new Bot(0, server.startIntegrated().client()));
                for (int i = 1; i < players; i++) bots.add(new Bot(i, server.connectLocalClient()));
                bots.forEach(Bot::start);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                long nextInput = System.nanoTime();
                while (System.nanoTime() < deadline) {
                    for (Bot bot : bots) bot.update();
                    long now = System.nanoTime();
                    if (now >= nextInput) {
                        for (Bot bot : bots) bot.move();
                        nextInput += TimeUnit.MILLISECONDS.toNanos(50);
                    }
                    Thread.sleep(1);
                }
                for (Bot bot : bots) bot.update();
                long joined = bots.stream().filter(Bot::joined).count();
                var tick = server.profiler().stats(
                        de.skyengine.server.profile.ServerProfiler.Phase.SERVER_TICK_TOTAL);
                var net = server.sessions().networkSnapshot();
                System.out.printf("Bots %d/%d joined, tick median/p95/max %.3f/%.3f/%.3f ms, "
                                + "RX/TX %d/%d packets, queues %d/%d%n", joined, players,
                        tick.medianMillis(), tick.p95Millis(), tick.maximumMillis(),
                        net.receivedPackets(), net.sentPackets(), net.inboundQueue(), net.outboundQueue());
                if (joined != players) throw new IllegalStateException("Only " + joined + " bots reached PLAY");
            }
        } finally {
            for (Bot bot : bots) bot.close();
            deleteTree(root);
        }
    }

    private static ServerConfig withMaxPlayers(ServerConfig value, int maxPlayers) {
        return new ServerConfig(value.serverDirectory(), value.bindAddress(), value.serverPort(), maxPlayers,
                value.viewDistance(), value.simulationDistance(), value.world(), value.motd(), value.tickRate(),
                value.keepAliveIntervalSeconds(), value.timeoutSeconds(), value.compression(),
                value.compressionLevel(), value.compressionThreshold(), value.maxPacketSize(),
                value.maxDecompressedPacketSize(), value.chunkBytesPerSecond(), value.autosaveIntervalTicks(),
                value.authentication(), value.workerThreads());
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static final class Bot implements AutoCloseable {
        private final int id;
        private final TransportConnection connection;
        private long sequence;
        private boolean joined;

        private Bot(int id, TransportConnection connection) { this.id = id; this.connection = connection; }

        void start() { send(new CorePackets.Handshake(EngineInfo.PROTOCOL_VERSION, EngineInfo.ENGINE_VERSION)); }

        void update() {
            PacketEnvelope envelope;
            while ((envelope = this.connection.pollInbound()) != null) handle(envelope.packet());
        }

        void move() {
            if (!this.joined) return;
            long next = ++this.sequence;
            send(new CorePackets.PlayerInput(new PlayerInputFrame(next, next, 1, 0,
                    this.id % 360, 0, 0)), next);
        }

        boolean joined() { return this.joined; }

        private void handle(Packet packet) {
            if (packet instanceof CorePackets.HandshakeAccepted) {
                send(new CorePackets.CompressionSelect("none"));
            } else if (packet instanceof CorePackets.CompressionEnabled) {
                this.connection.transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
                send(new CorePackets.LoginStart("Bot" + this.id, null));
            } else if (packet instanceof CorePackets.LoginSuccess) {
                this.connection.transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
            } else if (packet instanceof CorePackets.PackManifest) {
                send(new CorePackets.PackStatus(true, List.of()));
            } else if (packet instanceof CorePackets.RegistryFingerprint registry) {
                send(new CorePackets.ConfigurationAck(registry.sha256()));
                this.connection.transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
            } else if (packet instanceof CorePackets.RegistryData) {
                // Full clients retain mappings; load bots only verify the terminating fingerprint.
            } else if (packet instanceof CorePackets.JoinGame) {
                send(new CorePackets.ClientReady(0));
                this.connection.transitionState(ConnectionState.JOINING, ConnectionState.PLAY);
                this.joined = true;
            } else if (packet instanceof CorePackets.KeepAlive ping) {
                send(new CorePackets.KeepAliveResponse(ping.nonce()));
            } else if (packet instanceof CorePackets.Disconnect disconnect) {
                throw new IllegalStateException("Bot disconnected: " + disconnect.reason()
                        + ": " + disconnect.message());
            }
        }

        private void send(Packet packet) { send(packet, 0); }
        private void send(Packet packet, long sequence) {
            if (!this.connection.send(new PacketEnvelope(packet, sequence))) {
                throw new IllegalStateException("Bot outbound queue full");
            }
        }

        @Override public void close() { this.connection.close(); }
    }

    private MultiplayerLoadHarness() {
    }
}
