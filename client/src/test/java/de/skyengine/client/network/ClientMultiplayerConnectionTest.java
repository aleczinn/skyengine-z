package de.skyengine.client.network;

import de.skyengine.server.ServerConfig;
import de.skyengine.server.network.NettyTransportServer;
import de.skyengine.server.network.ServerSessionManager;
import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.ProtocolLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ClientMultiplayerConnectionTest {
    @TempDir Path temporaryDirectory;

    @Test void asynchronousTcpLifecycleReachesPlayWithoutBlockingOwnerThread() throws Exception {
        ServerSessionManager server = new ServerSessionManager(config());
        try (NettyTransportServer listener = new NettyTransportServer(CoreProtocol.createRegistry(),
                ProtocolLimits.MAX_FRAME_BYTES, server::accept);
             ClientMultiplayerConnection client = new ClientMultiplayerConnection()) {
            listener.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            var endpoint = (java.net.InetSocketAddress) listener.localAddress();
            client.connect(new ServerAddress("127.0.0.1", endpoint.getPort()), "GuiPlayer");

            long deadline = System.nanoTime() + 5_000_000_000L;
            long tick = 0;
            while (client.phase() != ClientMultiplayerConnection.Phase.PLAY
                    && System.nanoTime() < deadline) {
                server.tick(tick++, System.nanoTime());
                client.update();
                Thread.sleep(1);
            }

            assertEquals(ClientMultiplayerConnection.Phase.PLAY, client.phase(), client.detail());
            assertNotNull(client.joinGame());
        } finally {
            server.close();
        }
    }

    @Test void statusProbeReportsApplicationServerMetadataWithoutLogin() throws Exception {
        ServerSessionManager server = new ServerSessionManager(config());
        try (NettyTransportServer listener = new NettyTransportServer(CoreProtocol.createRegistry(),
                ProtocolLimits.MAX_FRAME_BYTES, 0, server::accept, server::statusResponse)) {
            listener.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            var endpoint = (java.net.InetSocketAddress) listener.localAddress();
            String address = new ServerAddress("127.0.0.1", endpoint.getPort()).display();
            ServerStatusPinger pinger = new ServerStatusPinger();
            pinger.refresh(java.util.List.of(address));

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (pinger.result(address).state() == ServerStatusPinger.State.CHECKING
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }

            ServerStatusPinger.Result result = pinger.result(address);
            assertEquals(ServerStatusPinger.State.ONLINE, result.state(), result.detail());
            assertEquals("test", result.motd());
            assertEquals(0, result.onlinePlayers());
            assertEquals(8, result.maxPlayers());
        } finally {
            server.close();
        }
    }

    private ServerConfig config() {
        return new ServerConfig(this.temporaryDirectory, "127.0.0.1", 25565, 8, 2, 2,
                "world", "test", EngineInfo.TICKS_PER_SECOND, 5, 30, "none", 1, 1024,
                ProtocolLimits.MAX_FRAME_BYTES, ProtocolLimits.MAX_DECOMPRESSED_BYTES,
                4 * 1024 * 1024, 1200, "offline", 2);
    }
}
