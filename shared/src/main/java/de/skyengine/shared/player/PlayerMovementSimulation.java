package de.skyengine.shared.player;

import java.util.Objects;

/**
 * Deterministic movement used by the current headless server and by client prediction.
 * Collision against the complete block-shape system replaces the ground sampler when the
 * legacy EntityPlayer physics is moved into the shared gameplay module.
 */
public final class PlayerMovementSimulation {
    @FunctionalInterface
    public interface GroundSampler {
        /** Returns the world-space Y coordinate on which the player's feet rest. */
        double groundY(double x, double z, double fallback);
    }

    private PlayerMovementSimulation() {}

    public static PlayerStateSnapshot simulate(PlayerStateSnapshot previous, PlayerInputFrame input,
                                                long serverTick, GroundSampler terrain) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(input);
        Objects.requireNonNull(terrain);

        PlayerGameMode mode = input.pressed(PlayerInputFrame.CYCLE_GAME_MODE)
                ? previous.gameMode().next() : previous.gameMode();
        float spectatorSpeed = mode == PlayerGameMode.SPECTATOR
                && previous.gameMode() != PlayerGameMode.SPECTATOR ? 1.0F : previous.spectatorFlySpeed();
        if (mode == PlayerGameMode.SPECTATOR) {
            if (input.pressed(PlayerInputFrame.SPECTATOR_SPEED_UP)) spectatorSpeed += 0.5F;
            if (input.pressed(PlayerInputFrame.SPECTATOR_SPEED_DOWN)) spectatorSpeed -= 0.5F;
            spectatorSpeed = Math.clamp(spectatorSpeed, 1.0F, 10.0F);
        }
        boolean flying = mode.alwaysFly()
                || (mode.canFly() && (previous.movementState() & PlayerMovementState.FLYING) != 0);
        if (input.pressed(PlayerInputFrame.TOGGLE_FLY) && mode == PlayerGameMode.CREATIVE) {
            flying = !flying;
        }
        if (!mode.canFly()) flying = false;

        double length = Math.hypot(input.forward(), input.strafe());
        double forward = length > 1 ? input.forward() / length : input.forward();
        double strafe = length > 1 ? input.strafe() / length : input.strafe();
        double yaw = Math.toRadians(input.yaw());
        boolean sprinting = input.pressed(PlayerInputFrame.SPRINT);
        boolean sneaking = input.pressed(PlayerInputFrame.SNEAK);

        double speed;
        if (flying) speed = mode == PlayerGameMode.SPECTATOR ? 0.8 * spectatorSpeed : sprinting ? 0.52 : 0.32;
        else speed = sneaking ? 0.08 : sprinting ? 0.26 : 0.18;
        double dx = (forward * Math.sin(yaw) + strafe * Math.cos(yaw)) * speed;
        double dz = (-forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * speed;
        double x = previous.x() + dx;
        double z = previous.z() + dz;
        double y = previous.y();
        double velocityY = previous.velocityY();
        boolean grounded;

        if (flying) {
            double vertical = mode == PlayerGameMode.SPECTATOR ? 0.8 * spectatorSpeed : 0.32;
            if (sprinting) vertical *= 1.625;
            velocityY = (input.pressed(PlayerInputFrame.JUMP) ? vertical : 0)
                    - (sneaking ? vertical : 0);
            y += velocityY;
            grounded = false;
        } else {
            double ground = terrain.groundY(x, z, previous.y());
            grounded = y <= ground + 0.001;
            if (grounded && input.pressed(PlayerInputFrame.JUMP)) {
                velocityY = 0.42;
                grounded = false;
            }
            if (!grounded) {
                velocityY = (velocityY - 0.08) * 0.98;
                y += velocityY;
                if (y <= ground) {
                    y = ground;
                    velocityY = 0;
                    grounded = true;
                }
            } else {
                y = ground;
                velocityY = 0;
            }
        }

        int movement = (flying ? PlayerMovementState.FLYING : 0)
                | (mode.alwaysFly() ? PlayerMovementState.NO_CLIP : 0)
                | (sprinting ? PlayerMovementState.SPRINTING : 0)
                | (sneaking ? PlayerMovementState.SNEAKING : 0);
        return new PlayerStateSnapshot(serverTick, input.sequence(), previous.dimension(), x, y, z,
                dx, velocityY, dz, input.yaw(), input.pitch(), grounded, mode, movement,
                previous.health(), previous.foodLevel(), previous.saturation(),
                input.selectedHotbarSlot(), previous.vehicleEntityId(), spectatorSpeed);
    }

    public static PlayerStateSnapshot withGameMode(PlayerStateSnapshot state, PlayerGameMode mode,
                                                    long serverTick) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(mode);
        int movement = state.movementState();
        if (!mode.canFly()) movement &= ~(PlayerMovementState.FLYING | PlayerMovementState.NO_CLIP);
        if (mode.alwaysFly()) movement |= PlayerMovementState.FLYING | PlayerMovementState.NO_CLIP;
        if (!mode.alwaysFly()) movement &= ~PlayerMovementState.NO_CLIP;
        return new PlayerStateSnapshot(serverTick, state.lastProcessedInputSequence(), state.dimension(),
                state.x(), state.y(), state.z(), state.velocityX(), state.velocityY(), state.velocityZ(),
                state.yaw(), state.pitch(), state.grounded(), mode, movement, state.health(),
                state.foodLevel(), state.saturation(), state.selectedHotbarSlot(), state.vehicleEntityId(),
                mode == PlayerGameMode.SPECTATOR && state.gameMode() != PlayerGameMode.SPECTATOR
                        ? 1.0F : state.spectatorFlySpeed());
    }
}
