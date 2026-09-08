package de.skyengine.shared.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflinePlayerNameTest {
    @Test
    void nameIsStableShortAndProtocolSafe() {
        UUID identity = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        String name = OfflinePlayerName.fromUuid(identity);

        assertEquals(name, OfflinePlayerName.fromUuid(identity));
        assertTrue(name.matches("[A-Za-z0-9]{3,16}"), name);
    }

    @Test
    void differentIdentitiesNormallyProduceDifferentNames() {
        assertNotEquals(OfflinePlayerName.fromUuid(new UUID(1, 2)),
                OfflinePlayerName.fromUuid(new UUID(3, 4)));
    }
}
