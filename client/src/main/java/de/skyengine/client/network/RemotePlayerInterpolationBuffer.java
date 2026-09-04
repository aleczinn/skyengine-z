package de.skyengine.client.network;

import de.skyengine.shared.player.PlayerStateSnapshot;

import java.util.ArrayDeque;

/** Fixed-delay tick interpolation for remote entities; late snapshots never rewind the buffer. */
public final class RemotePlayerInterpolationBuffer {
    private static final int MAX_SNAPSHOTS = 64;
    private final ArrayDeque<PlayerStateSnapshot> snapshots = new ArrayDeque<>();

    public void add(PlayerStateSnapshot snapshot) {
        if (!this.snapshots.isEmpty() && snapshot.serverTick() <= this.snapshots.getLast().serverTick()) return;
        this.snapshots.addLast(snapshot);
        while (this.snapshots.size() > MAX_SNAPSHOTS) this.snapshots.removeFirst();
    }

    public PlayerStateSnapshot sample(double renderServerTick) {
        if (this.snapshots.isEmpty()) return null;
        while (this.snapshots.size() >= 2) {
            var iterator = this.snapshots.iterator();
            PlayerStateSnapshot first = iterator.next(), second = iterator.next();
            if (second.serverTick() >= renderServerTick) break;
            this.snapshots.removeFirst();
        }
        PlayerStateSnapshot a = this.snapshots.getFirst();
        if (this.snapshots.size() == 1 || renderServerTick <= a.serverTick()) return a;
        PlayerStateSnapshot b = this.snapshots.stream().skip(1).findFirst().orElse(a);
        double span = b.serverTick() - a.serverTick();
        double alpha = span <= 0 ? 0 : Math.clamp((renderServerTick - a.serverTick()) / span, 0, 1);
        return new PlayerStateSnapshot((long) Math.floor(renderServerTick), b.lastProcessedInputSequence(),
                b.dimension(), lerp(a.x(), b.x(), alpha), lerp(a.y(), b.y(), alpha),
                lerp(a.z(), b.z(), alpha), lerp(a.velocityX(), b.velocityX(), alpha),
                lerp(a.velocityY(), b.velocityY(), alpha), lerp(a.velocityZ(), b.velocityZ(), alpha),
                lerpAngle(a.yaw(), b.yaw(), alpha), (float) lerp(a.pitch(), b.pitch(), alpha),
                alpha < 0.5 ? a.grounded() : b.grounded(),
                alpha < 0.5 ? a.gameMode() : b.gameMode(),
                alpha < 0.5 ? a.movementState() : b.movementState(),
                (float) lerp(a.health(), b.health(), alpha),
                alpha < 0.5 ? a.foodLevel() : b.foodLevel(),
                (float) lerp(a.saturation(), b.saturation(), alpha),
                alpha < 0.5 ? a.selectedHotbarSlot() : b.selectedHotbarSlot(),
                alpha < 0.5 ? a.vehicleEntityId() : b.vehicleEntityId(),
                alpha < 0.5 ? a.spectatorFlySpeed() : b.spectatorFlySpeed());
    }

    public int size() { return this.snapshots.size(); }

    private static double lerp(double a, double b, double alpha) { return a + (b - a) * alpha; }
    private static float lerpAngle(float a, float b, double alpha) {
        float delta = (b - a) % 360;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        return a + delta * (float) alpha;
    }
}
