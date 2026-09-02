package de.skyengine.graphics.gui.screens;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MultiplayerServerListTest {
    @TempDir Path temporaryDirectory;

    @Test void favoritesRoundTripAndRetainOrder() {
        Path file = this.temporaryDirectory.resolve("config/servers.json");
        MultiplayerServerList written = new MultiplayerServerList(file);
        written.add(new MultiplayerServerList.Entry("Local", "localhost"));
        written.add(new MultiplayerServerList.Entry("Remote", "example.org:25566"));
        written.set(0, new MultiplayerServerList.Entry("Localhost", "127.0.0.1"));

        MultiplayerServerList loaded = new MultiplayerServerList(file);
        assertEquals(2, loaded.entries().size());
        assertEquals("Localhost", loaded.entries().get(0).name());
        assertEquals("example.org:25566", loaded.entries().get(1).address());

        loaded.remove(0);
        assertEquals("Remote", new MultiplayerServerList(file).entries().getFirst().name());
    }
}
