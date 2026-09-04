package de.skyengine.server;

import de.skyengine.server.world.HeadlessWorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerApplicationTest {
    @Test void dedicatedServerTicksAndStopsWithoutGraphics(@TempDir Path root) throws Exception {
        ServerConfig defaults = ServerConfig.load(root);
        ServerConfig ephemeral = new ServerConfig(defaults.serverDirectory(), "127.0.0.1", 0,
                defaults.maxPlayers(), 2, 2, defaults.world(), defaults.motd(), defaults.tickRate(),
                defaults.keepAliveIntervalSeconds(), defaults.timeoutSeconds(), defaults.compression(),
                defaults.compressionLevel(), defaults.compressionThreshold(), defaults.maxPacketSize(),
                defaults.maxDecompressedPacketSize(), defaults.chunkBytesPerSecond(),
                defaults.autosaveIntervalTicks(), defaults.authentication(), defaults.workerThreads());
        try (ServerApplication server = new ServerApplication(ephemeral,
                new HeadlessWorldRuntime(ephemeral.worldDirectory()))) {
            server.startDedicated();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (server.serverTick() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(server.serverTick() >= 2);
        }
    }
}
