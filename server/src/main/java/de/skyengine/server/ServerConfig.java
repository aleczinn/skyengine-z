package de.skyengine.server;

import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ProtocolLimits;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ServerConfig(
        Path serverDirectory,
        String bindAddress,
        int serverPort,
        int maxPlayers,
        int viewDistance,
        int simulationDistance,
        String world,
        String motd,
        int tickRate,
        int keepAliveIntervalSeconds,
        int timeoutSeconds,
        String compression,
        int compressionLevel,
        int compressionThreshold,
        int maxPacketSize,
        int maxDecompressedPacketSize,
        int chunkBytesPerSecond,
        int autosaveIntervalTicks,
        String authentication,
        int workerThreads) {

    public static ServerConfig load(Path serverDirectory) throws IOException {
        Path root = serverDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path path = root.resolve("server.properties");
        Properties properties = defaults();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        } else {
            try (OutputStream output = Files.newOutputStream(path)) {
                properties.store(output, "SkyEngine dedicated server configuration");
            }
        }
        return fromProperties(root, properties);
    }

    public static ServerConfig fromProperties(Path root, Properties p) {
        ServerConfig config = new ServerConfig(root.toAbsolutePath().normalize(),
                text(p, "bind-address"), integer(p, "server-port"), integer(p, "max-players"),
                integer(p, "view-distance"), integer(p, "simulation-distance"), text(p, "world"),
                text(p, "motd"), integer(p, "tick-rate"), integer(p, "keepalive-interval"),
                integer(p, "timeout"), text(p, "compression"), integer(p, "compression-level"),
                integer(p, "compression-threshold"), integer(p, "max-packet-size"),
                integer(p, "max-decompressed-packet-size"), integer(p, "chunk-bytes-per-second"),
                integer(p, "autosave-interval-ticks"), text(p, "authentication"),
                integer(p, "worker-threads"));
        config.validate();
        return config;
    }

    public InetSocketAddress listenAddress() { return new InetSocketAddress(this.bindAddress, this.serverPort); }
    public Path worldDirectory() { return this.serverDirectory.resolve("worlds").resolve(this.world).normalize(); }

    /** Laufzeitkonfiguration fuer einen im Clientprozess gehosteten Server. */
    public static ServerConfig integrated(Path worldDirectory, int viewDistance, int simulationDistance) {
        Path directory = worldDirectory.toAbsolutePath().normalize();
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        return new ServerConfig(directory.getParent(), "127.0.0.1", 25565, 1,
                viewDistance, Math.min(simulationDistance, viewDistance), directory.getFileName().toString(),
                "Integrierter SkyEngine-Server", EngineInfo.TICKS_PER_SECOND, 5, 30,
                "none", 1, 1024, ProtocolLimits.MAX_FRAME_BYTES,
                ProtocolLimits.MAX_DECOMPRESSED_BYTES, 128 * 1024 * 1024, 1200,
                "offline", workers);
    }

    private void validate() {
        range("server-port", this.serverPort, 1, 65535);
        range("max-players", this.maxPlayers, 1, 10000);
        range("view-distance", this.viewDistance, 2, 32);
        range("simulation-distance", this.simulationDistance, 2, this.viewDistance);
        if (this.tickRate != EngineInfo.TICKS_PER_SECOND) {
            throw new IllegalArgumentException("tick-rate must be " + EngineInfo.TICKS_PER_SECOND);
        }
        range("keepalive-interval", this.keepAliveIntervalSeconds, 1, 60);
        range("timeout", this.timeoutSeconds, this.keepAliveIntervalSeconds * 2, 300);
        if (!this.compression.equals("zstd") && !this.compression.equals("none")) {
            throw new IllegalArgumentException("compression must be zstd or none");
        }
        range("compression-level", this.compressionLevel, -5, 22);
        range("compression-threshold", this.compressionThreshold, 0, ProtocolLimits.MAX_FRAME_BYTES);
        range("max-packet-size", this.maxPacketSize, 1024, ProtocolLimits.MAX_FRAME_BYTES);
        range("max-decompressed-packet-size", this.maxDecompressedPacketSize,
                this.maxPacketSize, ProtocolLimits.MAX_DECOMPRESSED_BYTES);
        range("chunk-bytes-per-second", this.chunkBytesPerSecond, 64 * 1024, 128 * 1024 * 1024);
        range("autosave-interval-ticks", this.autosaveIntervalTicks, 20, 1_000_000);
        range("worker-threads", this.workerThreads, 1, 256);
        if (!this.authentication.equals("offline")) {
            throw new IllegalArgumentException("Only authentication=offline is implemented");
        }
        if (!this.world.matches("[A-Za-z0-9._-]{1,128}") || this.world.equals(".") || this.world.equals("..")) {
            throw new IllegalArgumentException("Invalid world directory name");
        }
        if (this.motd.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("motd is too long");
        }
        Path worlds = this.serverDirectory.resolve("worlds").normalize();
        if (!worldDirectory().startsWith(worlds)) throw new IllegalArgumentException("World escapes server directory");
    }

    private static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("bind-address", "0.0.0.0");
        p.setProperty("server-port", "25565");
        p.setProperty("max-players", "8");
        p.setProperty("view-distance", "16");
        p.setProperty("simulation-distance", "10");
        p.setProperty("world", "world");
        p.setProperty("motd", "SkyEngine Server");
        p.setProperty("tick-rate", Integer.toString(EngineInfo.TICKS_PER_SECOND));
        p.setProperty("keepalive-interval", "5");
        p.setProperty("timeout", "30");
        p.setProperty("compression", "zstd");
        p.setProperty("compression-level", "1");
        p.setProperty("compression-threshold", "1024");
        p.setProperty("max-packet-size", Integer.toString(ProtocolLimits.MAX_FRAME_BYTES));
        p.setProperty("max-decompressed-packet-size", Integer.toString(ProtocolLimits.MAX_DECOMPRESSED_BYTES));
        p.setProperty("chunk-bytes-per-second", Integer.toString(4 * 1024 * 1024));
        p.setProperty("autosave-interval-ticks", "1200");
        p.setProperty("authentication", "offline");
        p.setProperty("worker-threads", Integer.toString(Math.max(2, Runtime.getRuntime().availableProcessors() - 2)));
        return p;
    }

    private static String text(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing property " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key) {
        try { return Integer.parseInt(text(p, key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer for " + key, e); }
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be in [" + minimum + ", " + maximum + "]");
        }
    }
}
