package de.skyengine.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @TempDir Path temporaryDirectory;

    @Test
    void firstLoadCreatesValidatedDedicatedServerConfiguration() throws Exception {
        ServerConfig config = ServerConfig.load(this.temporaryDirectory);
        assertTrue(Files.isRegularFile(this.temporaryDirectory.resolve("server.properties")));
        assertEquals(25565, config.serverPort());
        assertEquals(20, config.tickRate());
        assertTrue(config.worldDirectory().startsWith(this.temporaryDirectory.resolve("worlds")));
    }

    @Test
    void invalidRangesAndWorldNamesAreRejected() throws Exception {
        ServerConfig defaults = ServerConfig.load(this.temporaryDirectory);
        Properties properties = new Properties();
        try (var input = Files.newInputStream(this.temporaryDirectory.resolve("server.properties"))) {
            properties.load(input);
        }
        properties.setProperty("simulation-distance", "17");
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.fromProperties(this.temporaryDirectory, properties));
        properties.setProperty("simulation-distance", "10");
        properties.setProperty("world", "../escape");
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.fromProperties(this.temporaryDirectory, properties));
    }
}
