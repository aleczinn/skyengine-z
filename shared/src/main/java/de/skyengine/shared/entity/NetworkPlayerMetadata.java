package de.skyengine.shared.entity;

import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.player.PlayerMovementState;

import java.util.Objects;

/** Complete non-transform state needed to present a replicated player. */
public record NetworkPlayerMetadata(PlayerGameMode gameMode, int selectedSlot,
                                    int movementState, boolean grounded,
                                    NetworkItemStack heldItem) {
    public NetworkPlayerMetadata {
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(heldItem, "heldItem");
        if (selectedSlot < 0 || selectedSlot >= 9) {
            throw new IllegalArgumentException("Invalid selected hotbar slot");
        }
        if ((movementState & ~PlayerMovementState.KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException("Unknown player movement flags");
        }
    }
}
