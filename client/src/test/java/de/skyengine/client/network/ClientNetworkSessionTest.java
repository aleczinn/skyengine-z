package de.skyengine.client.network;

import de.skyengine.server.ServerConfig;
import de.skyengine.server.IntegratedServerHost;
import de.skyengine.server.network.NettyTransportServer;
import de.skyengine.server.network.ServerSessionManager;
import de.skyengine.server.network.PlayerSession;
import de.skyengine.server.player.OfflineIdentityProvider;
import de.skyengine.server.world.ServerWorldRuntime;
import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.ProtocolLimits;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.LocalTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientNetworkSessionTest {
    @TempDir Path temporaryDirectory;

    @Test
    void localTransportUsesTheCompletePacketSessionLifecycle() {
        ServerSessionManager server = new ServerSessionManager(config(25565));
        LocalTransport.Pair pair = LocalTransport.create();
        server.accept(pair.server());
        AtomicBoolean joined = new AtomicBoolean();
        ClientNetworkSession client = session(pair.client(), joined);
        client.start("LocalPlayer", null);

        for (int tick = 0; tick < 12 && client.state() != ConnectionState.PLAY; tick++) {
            server.tick(tick, 1_000_000L + tick);
            client.update();
        }
        server.tick(20, 2_000_000L);
        client.update();
        assertEquals(ConnectionState.PLAY, client.state());
        assertTrue(joined.get());
        assertEquals(1, server.sessions().size());
        server.close();
    }

    @Test
    void localTransportReturnsAuthoritativeCommandSuggestionsAndHints() {
        ServerWorldRuntime world = new ServerWorldRuntime() {
            @Override public Path directory() { return temporaryDirectory; }
            @Override public void tick(long serverTick) { }
            @Override public void autosave(long serverTick) { }
            @Override public List<String> suggestPlayerCommand(
                    de.skyengine.server.player.PlayerIdentity identity, String input) {
                return input.equals("/ki") ? List.of("/kill") : List.of();
            }
            @Override public String hintPlayerCommand(
                    de.skyengine.server.player.PlayerIdentity identity, String input) {
                return input.equals("/ki") ? " <target>" : "";
            }
            @Override public void close() { }
        };
        ServerConfig config = config(25565, 2);
        try (de.skyengine.server.ServerApplication application =
                     new de.skyengine.server.ServerApplication(config, world)) {
            LocalTransport.Pair pair = LocalTransport.create();
            application.sessions().accept(pair.server());
            ClientNetworkSession client = session(pair.client(), new AtomicBoolean());
            client.start("SuggestionPlayer", null);
            long tick = 0;
            while (client.state() != ConnectionState.PLAY && tick < 20) {
                application.sessions().tick(tick++, 1_000_000L + tick);
                client.update();
            }
            AtomicReference<CorePackets.CommandSuggestionsResponse> received = new AtomicReference<>();
            client.requestCommandSuggestions("/ki", 3, received::set);
            for (int i = 0; i < 4 && received.get() == null; i++) {
                application.sessions().tick(tick++, 2_000_000L + tick);
                client.update();
            }
            assertEquals(List.of("/kill"), received.get().suggestions());
            assertEquals(" <target>", received.get().hint());
        }
    }

    @Test
    void playerTargetedGamemodeAndTeleportCommandsMutateServerAuthority() {
        ServerWorldRuntime world = new ServerWorldRuntime() {
            @Override public Path directory() { return temporaryDirectory; }
            @Override public void tick(long serverTick) { }
            @Override public void autosave(long serverTick) { }
            @Override public de.skyengine.shared.player.PlayerStateSnapshot teleportPlayer(
                    de.skyengine.server.player.PlayerIdentity identity, int entityId,
                    de.skyengine.shared.player.PlayerStateSnapshot previous, String dimension,
                    double x, double y, double z, float yaw, float pitch, long serverTick) {
                return new de.skyengine.shared.player.PlayerStateSnapshot(serverTick,
                        previous.lastProcessedInputSequence(), dimension, x, y, z, 0, 0, 0,
                        yaw, pitch, false, previous.gameMode(), previous.movementState(),
                        previous.health(), previous.foodLevel(), previous.saturation(),
                        previous.selectedHotbarSlot(), previous.vehicleEntityId(),
                        previous.spectatorFlySpeed());
            }
            @Override public void close() { }
        };
        ServerConfig config = config(25565, 2);
        try (de.skyengine.server.ServerApplication application =
                     new de.skyengine.server.ServerApplication(config, world)) {
            LocalTransport.Pair firstPair = LocalTransport.create();
            LocalTransport.Pair secondPair = LocalTransport.create();
            application.sessions().accept(firstPair.server());
            application.sessions().accept(secondPair.server());
            AtomicReference<CorePackets.CommandResult> commandResult = new AtomicReference<>();
            ClientNetworkSession first = new ClientNetworkSession(firstPair.client(),
                    new ReplicatedChunkCache(null),
                    packs -> ClientNetworkSession.PackValidation.acceptAll(),
                    new ClientNetworkSession.Listener() {
                        @Override public void commandResult(CorePackets.CommandResult result) {
                            commandResult.set(result);
                        }
                    });
            ClientNetworkSession second = session(secondPair.client(), new AtomicBoolean());
            first.start("Alpha", null);
            second.start("Beta", null);
            long tick = 0;
            while ((first.state() != ConnectionState.PLAY || second.state() != ConnectionState.PLAY)
                    && tick < 30) {
                application.sessions().tick(tick++, 1_000_000L + tick);
                first.update(); second.update();
            }
            // Let the server consume both ClientReady messages as well.
            application.sessions().tick(tick++, 1_500_000L + tick);
            first.update(); second.update();

            PlayerSession beta = application.sessions().sessions().stream().skip(1).findFirst().orElseThrow();
            String targetName = beta.identity().name();
            first.sendCommand(1, "/gamemode " + targetName + " spectator");
            for (int i = 0; i < 4; i++) {
                first.update(); second.update();
                application.sessions().tick(tick++, 2_000_000L + tick);
            }
            assertTrue(commandResult.get() != null, "server did not answer command");
            assertTrue(commandResult.get().success(), commandResult.get().messages().toString());
            assertEquals(de.skyengine.shared.player.PlayerGameMode.SPECTATOR,
                    beta.playerState().gameMode());

            first.sendCommand(2, "/tp " + targetName + " 12 34 -5");
            for (int i = 0; i < 4; i++) {
                first.update(); second.update();
                application.sessions().tick(tick++, 3_000_000L + tick);
            }
            assertEquals(12, beta.playerState().x());
            assertEquals(34, beta.playerState().y());
            assertEquals(-5, beta.playerState().z());
        }
    }

    @Test
    void integratedServerHostUsesTheSameAuthoritativePacketLifecycle() throws Exception {
        ServerConfig config = config(25565, 2);
        try (IntegratedServerHost host = new IntegratedServerHost(config, snapshotWorld())) {
            AtomicBoolean joined = new AtomicBoolean();
            ClientNetworkSession client = session(host.clientConnection(), joined);
            client.start("IntegratedPlayer", null);
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (client.state() != ConnectionState.PLAY && System.nanoTime() < deadline) {
                client.update();
                Thread.sleep(1);
            }
            client.update();
            assertEquals(ConnectionState.PLAY, client.state());
            assertTrue(joined.get());
            assertEquals(1, host.server().sessions().sessions().size());
        }
    }

    @Test
    void joiningPlayerAndExistingPlayerReceiveEachOtherExactlyOnce() {
        ServerSessionManager server = new ServerSessionManager(config(25565));
        LocalTransport.Pair firstPair = LocalTransport.create();
        LocalTransport.Pair secondPair = LocalTransport.create();
        List<String> firstSees = new ArrayList<>();
        List<String> secondSees = new ArrayList<>();
        ClientNetworkSession first = playerSession(firstPair.client(), firstSees);
        ClientNetworkSession second = playerSession(secondPair.client(), secondSees);

        server.accept(firstPair.server());
        first.start("FirstPlayer", null);
        for (int tick = 0; tick < 16 && first.state() != ConnectionState.PLAY; tick++) {
            server.tick(tick, 1_000_000L + tick);
            first.update();
        }
        assertEquals(ConnectionState.PLAY, first.state());

        server.accept(secondPair.server());
        second.start("SecondPlayer", null);
        for (int tick = 16; tick < 40 && second.state() != ConnectionState.PLAY; tick++) {
            server.tick(tick, 1_000_000L + tick);
            first.update();
            second.update();
        }
        server.tick(40, 1_000_040L);
        first.update();
        second.update();

        assertEquals(ConnectionState.PLAY, second.state());
        assertEquals(List.of("SecondPlayer"), firstSees);
        assertEquals(List.of("FirstPlayer"), secondSees);
        server.close();
    }

    @Test
    void tcpTransportHandlesBackToBackConnectionStatesWithoutIoThreadRace() throws Exception {
        ServerSessionManager server = new ServerSessionManager(config(0, 16, "zstd", 1));
        try (NettyTransportServer listener = new NettyTransportServer(CoreProtocol.createRegistry(),
                ProtocolLimits.MAX_FRAME_BYTES, server::accept)) {
            listener.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            try (NettyClientTransport transport = new NettyClientTransport(CoreProtocol.createRegistry(),
                    ProtocolLimits.MAX_FRAME_BYTES)) {
                var connection = transport.connect(listener.localAddress());
                AtomicBoolean joined = new AtomicBoolean();
                ClientNetworkSession client = session(connection, joined);
                client.start("TcpPlayer", null);
                long deadline = System.nanoTime() + 5_000_000_000L;
                long tick = 0;
                while (client.state() != ConnectionState.PLAY && System.nanoTime() < deadline) {
                    server.tick(tick++, System.nanoTime());
                    client.update();
                    Thread.onSpinWait();
                }
                server.tick(tick, System.nanoTime());
                client.update();
                assertEquals(ConnectionState.PLAY, client.state());
                assertTrue(joined.get());
            }
        } finally {
            server.close();
        }
    }

    @Test
    void initialChunkInterestStreamsOnlyCompletedSnapshotsIntoClientCache() {
        ServerWorldRuntime world = new ServerWorldRuntime() {
            @Override public Path directory() { return temporaryDirectory; }
            @Override public void tick(long serverTick) { }
            @Override public void autosave(long serverTick) { }
            @Override public de.skyengine.server.world.ChunkSnapshotTicket
                    requestChunkSnapshot(String dimension, int chunkX, int chunkZ) {
                var snapshot = new de.skyengine.shared.world.ChunkColumnSnapshot(dimension, chunkX, chunkZ, 0,
                        List.of(), new int[1024], new int[1089], new int[1089], new int[1024]);
                return de.skyengine.server.world.ChunkSnapshotTicket.completed(Optional.of(snapshot));
            }
            @Override public void close() { }
        };
        ServerConfig config = config(25565, 2);
        ServerSessionManager server = new ServerSessionManager(config, new OfflineIdentityProvider(), world);
        LocalTransport.Pair pair = LocalTransport.create();
        server.accept(pair.server());
        ReplicatedChunkCache chunks = new ReplicatedChunkCache(null);
        ClientNetworkSession client = new ClientNetworkSession(pair.client(), chunks,
                packs -> ClientNetworkSession.PackValidation.acceptAll(), null);
        client.start("ChunkPlayer", null);
        for (int tick = 0; tick < 30; tick++) {
            server.tick(tick, 1_000_000L + tick);
            client.update();
        }
        assertEquals(ConnectionState.PLAY, client.state());
        /* Visible radius 2 plus its exact one-column Chebyshev meshing halo. */
        assertEquals(37, chunks.size());
        assertTrue(chunks.lastCompletedBatch() >= 37);
        server.close();
    }

    @Test
    void tcpChunkBatchesAreEncodedAsynchronouslyAndArriveAtomically() throws Exception {
        ServerWorldRuntime world = snapshotWorld();
        ServerSessionManager server = new ServerSessionManager(config(0, 2, "zstd", 1),
                new OfflineIdentityProvider(), world);
        try (NettyTransportServer listener = new NettyTransportServer(CoreProtocol.createRegistry(),
                ProtocolLimits.MAX_FRAME_BYTES, 1, server::accept)) {
            listener.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            try (NettyClientTransport transport = new NettyClientTransport(CoreProtocol.createRegistry(),
                    ProtocolLimits.MAX_FRAME_BYTES)) {
                ReplicatedChunkCache chunks = new ReplicatedChunkCache(null);
                ClientNetworkSession client = new ClientNetworkSession(transport.connect(listener.localAddress()),
                        chunks, packs -> ClientNetworkSession.PackValidation.acceptAll(), null);
                client.start("TcpChunkPlayer", null);
                long deadline = System.nanoTime() + 5_000_000_000L;
                long tick = 0;
                while (chunks.size() < 37 && System.nanoTime() < deadline) {
                    server.tick(tick++, System.nanoTime());
                    client.update();
                    Thread.sleep(1);
                }
                assertEquals(ConnectionState.PLAY, client.state());
                assertEquals(37, chunks.size());
                assertTrue(server.networkSnapshot().chunkBatchesEncoded() >= 37);
            }
        } finally { server.close(); }
    }

    private ServerWorldRuntime snapshotWorld() {
        return new ServerWorldRuntime() {
            @Override public Path directory() { return temporaryDirectory; }
            @Override public void tick(long serverTick) { }
            @Override public void autosave(long serverTick) { }
            @Override public de.skyengine.server.world.ChunkSnapshotTicket
                    requestChunkSnapshot(String dimension, int chunkX, int chunkZ) {
                return de.skyengine.server.world.ChunkSnapshotTicket.completed(Optional.of(
                        new de.skyengine.shared.world.ChunkColumnSnapshot(dimension, chunkX, chunkZ, 0,
                                List.of(), new int[1024], new int[1089], new int[1089], new int[1024])));
            }
            @Override public void close() { }
        };
    }

    private ClientNetworkSession session(de.skyengine.shared.network.transport.TransportConnection connection,
                                         AtomicBoolean joined) {
        return new ClientNetworkSession(connection, new ReplicatedChunkCache(null),
                packs -> ClientNetworkSession.PackValidation.acceptAll(),
                new ClientNetworkSession.Listener() {
                    @Override public void joined(CorePackets.JoinGame packet) { joined.set(true); }
                });
    }

    private ClientNetworkSession playerSession(
            de.skyengine.shared.network.transport.TransportConnection connection, List<String> seenPlayers) {
        return new ClientNetworkSession(connection, new ReplicatedChunkCache(null),
                packs -> ClientNetworkSession.PackValidation.acceptAll(),
                new ClientNetworkSession.Listener() {
                    @Override public void playerJoined(CorePackets.PlayerJoined packet) {
                        seenPlayers.add(packet.username());
                    }
                });
    }

    private ServerConfig config(int port) {
        return config(port, 16);
    }

    private ServerConfig config(int port, int viewDistance) {
        return config(port, viewDistance, "none", 1024);
    }

    private ServerConfig config(int port, int viewDistance, String compression, int threshold) {
        return new ServerConfig(this.temporaryDirectory, "127.0.0.1", port, 8, viewDistance,
                Math.min(viewDistance, 10),
                "world", "test", EngineInfo.TICKS_PER_SECOND, 5, 30, compression, 1, threshold,
                ProtocolLimits.MAX_FRAME_BYTES, ProtocolLimits.MAX_DECOMPRESSED_BYTES,
                4 * 1024 * 1024, 1200, "offline", 2);
    }
}
