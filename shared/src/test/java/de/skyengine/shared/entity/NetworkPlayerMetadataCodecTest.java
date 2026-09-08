package de.skyengine.shared.entity;

import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.player.PlayerMovementState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkPlayerMetadataCodecTest {
    @Test
    void roundTripsCompletePresentationState() {
        NetworkPlayerMetadata expected = new NetworkPlayerMetadata(PlayerGameMode.CREATIVE, 7,
                PlayerMovementState.FLYING | PlayerMovementState.SNEAKING, true,
                new NetworkItemStack(42, 12, new byte[]{1, 2, 3}));

        NetworkPlayerMetadata actual = NetworkPlayerMetadataCodec.decode(
                NetworkPlayerMetadataCodec.encode(expected));

        assertEquals(expected.gameMode(), actual.gameMode());
        assertEquals(expected.selectedSlot(), actual.selectedSlot());
        assertEquals(expected.movementState(), actual.movementState());
        assertEquals(expected.grounded(), actual.grounded());
        assertEquals(expected.heldItem().itemId(), actual.heldItem().itemId());
        assertEquals(expected.heldItem().count(), actual.heldItem().count());
        assertArrayEquals(expected.heldItem().components(), actual.heldItem().components());
    }

    @Test
    void rejectsUnknownMovementFlags() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkPlayerMetadata(
                PlayerGameMode.SURVIVAL, 0, 1 << 20, false, NetworkItemStack.empty()));
    }
}
