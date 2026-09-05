package de.skyengine.server.network;

import de.skyengine.server.ServerApplication;
import de.skyengine.server.ServerCommandDispatcher;
import de.skyengine.server.ServerConfig;
import de.skyengine.server.world.HeadlessWorldRuntime;
import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ProtocolLimits;
import de.skyengine.shared.network.transport.LocalTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCommandDispatcherTest {
    @TempDir Path temporaryDirectory;

    @Test
    void consoleAndRemoteSourcesShareCommandsButKeepPrivilegesSeparate() throws Exception {
        ServerConfig config = new ServerConfig(this.temporaryDirectory, "127.0.0.1", 25565,
                8, 2, 2, "world", "test", EngineInfo.TICKS_PER_SECOND, 5, 30,
                "none", 1, 1024, ProtocolLimits.MAX_FRAME_BYTES,
                ProtocolLimits.MAX_DECOMPRESSED_BYTES, 4 * 1024 * 1024, 1200, "offline", 2);
        try (ServerApplication server = new ServerApplication(config,
                new HeadlessWorldRuntime(config.worldDirectory()))) {
            LocalTransport.Pair pair = LocalTransport.create();
            server.sessions().accept(pair.server());
            server.sessions().processNetwork(0, 0);
            PlayerSession remote = server.sessions().sessions().iterator().next();

            assertTrue(server.commands().execute("list", null).success());
            assertTrue(server.commands().execute("perf", null).success());
            assertTrue(server.commands().execute("net", remote).success());
            assertTrue(server.commands().execute("profile start", null).success());
            assertTrue(server.commands().execute("profile", null).success());
            assertTrue(server.commands().execute("profile stop", null).success());
            assertFalse(server.commands().execute("stop", remote).success());
            assertFalse(server.stopRequested());

            ServerCommandDispatcher.Result stop = server.commands().execute("stop", null);
            assertTrue(stop.success());
            assertTrue(server.stopRequested());
        }
    }
}
