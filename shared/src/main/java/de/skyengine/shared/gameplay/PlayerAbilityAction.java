package de.skyengine.shared.gameplay;

/**
 * Reliable, edge-triggered player abilities. Continuous movement remains in {@code PlayerInputFrame};
 * actions in this enum must be applied exactly once by the authoritative server.
 */
public enum PlayerAbilityAction {
    CYCLE_GAME_MODE,
    TOGGLE_FLY,
    SPECTATOR_SPEED_UP,
    SPECTATOR_SPEED_DOWN
}
