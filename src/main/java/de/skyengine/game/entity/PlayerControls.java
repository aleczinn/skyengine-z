package de.skyengine.game.entity;

/**
 * Renderer- and device-independent movement input consumed by the authoritative player physics.
 * A graphical client, a remote network session and a bot all produce the same value.
 */
public record PlayerControls(float forward, float strafe, boolean jump, boolean sneak,
                             boolean sprint, boolean sneakToggle, boolean sprintToggle) {
    public static final PlayerControls NONE = new PlayerControls(0, 0, false, false,
            false, false, false);

    public PlayerControls {
        if (!Float.isFinite(forward) || !Float.isFinite(strafe)
                || forward < -1 || forward > 1 || strafe < -1 || strafe > 1) {
            throw new IllegalArgumentException("Movement axes must be finite and in [-1, 1]");
        }
    }
}
