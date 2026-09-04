package de.skyengine.shared.player;

import java.util.Objects;

public record PlayerStateSnapshot(long serverTick, long lastProcessedInputSequence, String dimension,
                                  double x, double y, double z, double velocityX, double velocityY,
                                  double velocityZ, float yaw, float pitch, boolean grounded,
                                  PlayerGameMode gameMode, int movementState, float health,
                                  int foodLevel, float saturation, int selectedHotbarSlot,
                                  int vehicleEntityId, float spectatorFlySpeed) {
    public PlayerStateSnapshot {
        if (serverTick < 0 || lastProcessedInputSequence < 0) throw new IllegalArgumentException("Negative tick/sequence");
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(gameMode);
        if (!finite(x, y, z, velocityX, velocityY, velocityZ)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException("Non-finite player state");
        }
        if ((movementState & ~PlayerMovementState.KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException("Unknown movement-state flags");
        }
        if (!Float.isFinite(health) || health < 0 || health > 20
                || foodLevel < 0 || foodLevel > 20 || !Float.isFinite(saturation)
                || saturation < 0 || saturation > 20 || selectedHotbarSlot < 0
                || selectedHotbarSlot > 8 || vehicleEntityId < 0
                || !Float.isFinite(spectatorFlySpeed)
                || spectatorFlySpeed < 1.0F || spectatorFlySpeed > 10.0F) {
            throw new IllegalArgumentException("Invalid player vitals or selected slot");
        }
    }

    public PlayerStateSnapshot(long serverTick, long lastProcessedInputSequence, String dimension,
                               double x, double y, double z, double velocityX, double velocityY,
                               double velocityZ, float yaw, float pitch, boolean grounded,
                               PlayerGameMode gameMode, int movementState, float health,
                               int foodLevel, float saturation, int selectedHotbarSlot,
                               int vehicleEntityId) {
        this(serverTick, lastProcessedInputSequence, dimension, x, y, z, velocityX, velocityY,
                velocityZ, yaw, pitch, grounded, gameMode, movementState, health, foodLevel,
                saturation, selectedHotbarSlot, vehicleEntityId, 1.0F);
    }

    public PlayerStateSnapshot(long serverTick, long lastProcessedInputSequence, String dimension,
                               double x, double y, double z, double velocityX, double velocityY,
                               double velocityZ, float yaw, float pitch, boolean grounded,
                               PlayerGameMode gameMode, int movementState, float health,
                               int foodLevel, float saturation, int selectedHotbarSlot) {
        this(serverTick, lastProcessedInputSequence, dimension, x, y, z, velocityX, velocityY,
                velocityZ, yaw, pitch, grounded, gameMode, movementState, health, foodLevel,
                saturation, selectedHotbarSlot, 0, 1.0F);
    }

    public PlayerStateSnapshot(long serverTick, long lastProcessedInputSequence, String dimension,
                               double x, double y, double z, double velocityX, double velocityY,
                               double velocityZ, float yaw, float pitch, boolean grounded,
                               PlayerGameMode gameMode, int movementState) {
        this(serverTick, lastProcessedInputSequence, dimension, x, y, z, velocityX, velocityY,
                velocityZ, yaw, pitch, grounded, gameMode, movementState, 20, 20, 5, 0, 0);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
