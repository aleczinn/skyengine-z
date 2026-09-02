package de.skyengine.client.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ServerAddressTest {
    @Test void parsesDefaultAndExplicitPorts() {
        assertEquals(new ServerAddress("example.org", 25565), ServerAddress.parse("example.org"));
        assertEquals(new ServerAddress("localhost", 24444), ServerAddress.parse(" localhost:24444 "));
    }

    @Test void parsesBracketedIpv6() {
        ServerAddress address = ServerAddress.parse("[::1]:25566");
        assertEquals("::1", address.host());
        assertEquals(25566, address.port());
        assertEquals("[::1]:25566", address.display());
    }

    @Test void rejectsInvalidPortsAndEmptyHosts() {
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse(":25565"));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("localhost:0"));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("localhost:nope"));
    }
}
