package de.skyengine.shared.player;

/** Bit layout of {@link PlayerStateSnapshot#movementState()}. */
public final class PlayerMovementState {
    public static final int FLYING = 1;
    public static final int NO_CLIP = 1 << 1;
    public static final int SPRINTING = 1 << 2;
    public static final int SNEAKING = 1 << 3;
    public static final int KNOWN_FLAGS = FLYING | NO_CLIP | SPRINTING | SNEAKING;

    private PlayerMovementState() {}
}
