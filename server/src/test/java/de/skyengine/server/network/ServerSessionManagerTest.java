package de.skyengine.server.network;

import de.skyengine.server.ServerConfig;
import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.Packet;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.LocalTransport;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.player.PlayerGameMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSessionManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void localClientCompletesAuthoritativeJoinStateMachine() {
        ServerSessionManager manager = new ServerSessionManager(config());
        List<String> lifecycle = new ArrayList<>();
        manager.lifecycleLogger(lifecycle::add);
        LocalTransport.Pair pair = LocalTransport.create();
        long now = 1_000_000L;
        manager.accept(pair.server());
        manager.tick(0, now);

        send(pair.client(), new CorePackets.Handshake(EngineInfo.PROTOCOL_VERSION, EngineInfo.ENGINE_VERSION));
        manager.tick(1, now + 1);
        assertInstanceOf(CorePackets.HandshakeAccepted.class, pair.client().pollInbound().packet());
        send(pair.client(), new CorePackets.CompressionSelect("none"));
        manager.tick(2, now + 2);
        assertInstanceOf(CorePackets.CompressionEnabled.class, pair.client().pollInbound().packet());
        pair.client().transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);

        send(pair.client(), new CorePackets.LoginStart("Alec", null));
        manager.tick(3, now + 3);
        List<Packet> configuration = drain(pair.client());
        assertTrue(configuration.stream().anyMatch(CorePackets.LoginSuccess.class::isInstance));
        CorePackets.RegistryFingerprint fingerprint = configuration.stream()
                .filter(CorePackets.RegistryFingerprint.class::isInstance)
                .map(CorePackets.RegistryFingerprint.class::cast).findFirst().orElseThrow();
        assertTrue(configuration.stream().anyMatch(CorePackets.PackManifest.class::isInstance));
        assertTrue(configuration.stream().anyMatch(CorePackets.RegistryData.class::isInstance));
        pair.client().transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);

        send(pair.client(), new CorePackets.PackStatus(true, List.of()));
        send(pair.client(), new CorePackets.ConfigurationAck(fingerprint.sha256()));
        manager.tick(4, now + 4);
        assertTrue(drain(pair.client()).stream().anyMatch(CorePackets.JoinGame.class::isInstance));
        pair.client().transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);

        send(pair.client(), new CorePackets.ClientReady(0));
        manager.tick(5, now + 5);
        pair.client().transitionState(ConnectionState.JOINING, ConnectionState.PLAY);

        PlayerSession session = manager.sessions().iterator().next();
        assertEquals(ConnectionState.PLAY, session.state());
        assertEquals("Alec", session.identity().name());
        assertTrue(manager.setGameMode(session, PlayerGameMode.SPECTATOR));
        assertEquals(PlayerGameMode.SPECTATOR, session.playerState().gameMode());
        assertFalse(drain(pair.client()).stream().anyMatch(CorePackets.PlayerJoined.class::isInstance),
                "A player must not receive a synthetic self-join notification");
        assertTrue(lifecycle.stream().anyMatch(message -> message.startsWith("Player login accepted: Alec")));
        assertTrue(lifecycle.stream().anyMatch(message -> message.startsWith("Player joined: Alec")));
        manager.close();
        assertTrue(lifecycle.stream().anyMatch(message -> message.equals("Player left: Alec (SERVER_STOP)")));
    }

    @Test
    void protocolMismatchAndTimeoutCloseSessionCleanly() {
        ServerSessionManager manager = new ServerSessionManager(config());
        LocalTransport.Pair mismatch = LocalTransport.create();
        manager.accept(mismatch.server());
        manager.tick(0, 0);
        send(mismatch.client(), new CorePackets.Handshake(EngineInfo.PROTOCOL_VERSION + 1, "future"));
        manager.tick(1, 1);
        assertFalse(mismatch.client().open());
        assertTrue(manager.sessions().isEmpty());
        CorePackets.Disconnect disconnect = assertInstanceOf(CorePackets.Disconnect.class,
                mismatch.client().pollInbound().packet());
        assertEquals(de.skyengine.shared.network.DisconnectReason.PROTOCOL_MISMATCH, disconnect.reason());

        LocalTransport.Pair idle = LocalTransport.create();
        manager.accept(idle.server());
        manager.tick(2, 2);
        manager.tick(3, 31_000_000_003L);
        assertFalse(idle.client().open());
        assertTrue(manager.sessions().isEmpty());
        manager.close();
    }

    @Test
    void rejectedPackManifestStopsConfigurationBeforeJoin() {
        ServerSessionManager manager = new ServerSessionManager(config());
        LocalTransport.Pair pair = LocalTransport.create();
        manager.accept(pair.server());
        manager.tick(0, 0);
        send(pair.client(), new CorePackets.Handshake(EngineInfo.PROTOCOL_VERSION, EngineInfo.ENGINE_VERSION));
        manager.tick(1, 1);
        drain(pair.client());
        send(pair.client(), new CorePackets.CompressionSelect("none"));
        manager.tick(2, 2);
        drain(pair.client());
        pair.client().transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
        send(pair.client(), new CorePackets.LoginStart("MissingPacks", null));
        manager.tick(3, 3);
        drain(pair.client());
        pair.client().transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);

        send(pair.client(), new CorePackets.PackStatus(false, List.of("required:test-pack")));
        manager.tick(4, 4);

        CorePackets.Disconnect disconnect = assertInstanceOf(CorePackets.Disconnect.class,
                pair.client().pollInbound().packet());
        assertEquals(de.skyengine.shared.network.DisconnectReason.PACK_MISMATCH, disconnect.reason());
        assertTrue(manager.sessions().isEmpty());
        manager.close();
    }

    @Test
    void gameplayPacketDuringHandshakeIsRejected() {
        ServerSessionManager manager = new ServerSessionManager(config());
        LocalTransport.Pair pair = LocalTransport.create();
        manager.accept(pair.server());
        manager.tick(0, 0);

        send(pair.client(), new CorePackets.ClientReady(0));
        manager.tick(1, 1);

        CorePackets.Disconnect disconnect = assertInstanceOf(CorePackets.Disconnect.class,
                pair.client().pollInbound().packet());
        assertEquals(de.skyengine.shared.network.DisconnectReason.INVALID_PACKET, disconnect.reason());
        assertTrue(manager.sessions().isEmpty());
        manager.close();
    }

    private ServerConfig config() {
        return new ServerConfig(this.temporaryDirectory, "127.0.0.1", 25565, 8, 16, 10,
                "world", "test", EngineInfo.TICKS_PER_SECOND, 5, 30, "none", 1, 1024,
                de.skyengine.shared.network.ProtocolLimits.MAX_FRAME_BYTES,
                de.skyengine.shared.network.ProtocolLimits.MAX_DECOMPRESSED_BYTES,
                4 * 1024 * 1024, 1200,
                "offline", 2);
    }

    private static void send(TransportConnection connection, Packet packet) {
        assertTrue(connection.send(new PacketEnvelope(packet)));
    }

    private static List<Packet> drain(TransportConnection connection) {
        List<Packet> packets = new ArrayList<>();
        PacketEnvelope envelope;
        while ((envelope = connection.pollInbound()) != null) packets.add(envelope.packet());
        return packets;
    }
}
