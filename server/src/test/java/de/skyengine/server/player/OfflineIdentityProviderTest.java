package de.skyengine.server.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OfflineIdentityProviderTest {
    @Test void honoursEphemeralClientIdentityAlsoForTcpConnections() throws Exception {
        OfflineIdentityProvider provider = new OfflineIdentityProvider();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();

        assertEquals(first, provider.authenticate("Alec", first, false).uuid());
        assertEquals(second, provider.authenticate("Alec", second, false).uuid());
        assertNotEquals(first, second);
    }

    @Test void missingClientIdentityKeepsDeterministicOfflineFallback() throws Exception {
        OfflineIdentityProvider provider = new OfflineIdentityProvider();
        assertEquals(provider.authenticate("Alec", null, false).uuid(),
                provider.authenticate("Alec", null, false).uuid());
    }
}
