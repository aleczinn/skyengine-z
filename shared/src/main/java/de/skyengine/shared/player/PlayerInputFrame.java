package de.skyengine.shared.player;

public record PlayerInputFrame(long sequence, long clientTick, float forward, float strafe,
                               float yaw, float pitch, int buttons, int selectedHotbarSlot) {
    public static final int JUMP = 1;
    public static final int SNEAK = 1 << 1;
    public static final int SPRINT = 1 << 2;
    public static final int USE = 1 << 3;
    public static final int ATTACK = 1 << 4;
    public static final int CYCLE_GAME_MODE = 1 << 5;
    public static final int TOGGLE_FLY = 1 << 6;
    public static final int SNEAK_TOGGLE_MODE = 1 << 7;
    public static final int SPRINT_TOGGLE_MODE = 1 << 8;
    public static final int SPECTATOR_SPEED_UP = 1 << 9;
    public static final int SPECTATOR_SPEED_DOWN = 1 << 10;
    public static final int KNOWN_BUTTONS = JUMP | SNEAK | SPRINT | USE | ATTACK
            | CYCLE_GAME_MODE | TOGGLE_FLY | SNEAK_TOGGLE_MODE | SPRINT_TOGGLE_MODE
            | SPECTATOR_SPEED_UP | SPECTATOR_SPEED_DOWN;

    public PlayerInputFrame {
        if (sequence < 0 || clientTick < 0) throw new IllegalArgumentException("Negative movement sequence/tick");
        if (!Float.isFinite(forward) || !Float.isFinite(strafe)
                || forward < -1 || forward > 1 || strafe < -1 || strafe > 1) {
            throw new IllegalArgumentException("Invalid movement axes");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException("Invalid player rotation");
        }
        if ((buttons & ~KNOWN_BUTTONS) != 0) throw new IllegalArgumentException("Unknown input buttons");
        if (selectedHotbarSlot < 0 || selectedHotbarSlot > 8) {
            throw new IllegalArgumentException("Invalid selected hotbar slot");
        }
    }

    /** Source-compatible constructor for simulations that do not model inventory selection. */
    public PlayerInputFrame(long sequence, long clientTick, float forward, float strafe,
                            float yaw, float pitch, int buttons) {
        this(sequence, clientTick, forward, strafe, yaw, pitch, buttons, 0);
    }

    public boolean pressed(int button) { return (this.buttons & button) != 0; }
}
