package de.skyengine.client.network;

import de.skyengine.shared.entity.NetworkEntitySnapshot;

import java.util.ArrayDeque;

/** Fixed-delay interpolation for replicated entity transforms. */
public final class RemoteEntityInterpolationBuffer {
    private static final int MAX_SNAPSHOTS = 64;
    private static final double TELEPORT_DISTANCE_SQUARED = 16.0 * 16.0;
    private final ArrayDeque<NetworkEntitySnapshot> snapshots = new ArrayDeque<>();
    private boolean discontinuity;

    public void add(NetworkEntitySnapshot snapshot) {
        if (!this.snapshots.isEmpty() && snapshot.revision() <= this.snapshots.getLast().revision()) return;
        if (!this.snapshots.isEmpty()) {
            NetworkEntitySnapshot previous = this.snapshots.getLast();
            double dx = snapshot.x() - previous.x(), dy = snapshot.y() - previous.y(),
                    dz = snapshot.z() - previous.z();
            if (!snapshot.dimension().equals(previous.dimension())
                    || dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE_SQUARED) {
                this.snapshots.clear();
                this.discontinuity = true;
            }
        }
        this.snapshots.addLast(snapshot);
        while (this.snapshots.size() > MAX_SNAPSHOTS) this.snapshots.removeFirst();
    }

    public double latestTick() {
        return this.snapshots.isEmpty() ? 0 : this.snapshots.getLast().revision();
    }

    public NetworkEntitySnapshot sample(double renderTick) {
        if (this.snapshots.isEmpty()) return null;
        while (this.snapshots.size() >= 2) {
            var iterator = this.snapshots.iterator();
            iterator.next();
            NetworkEntitySnapshot second = iterator.next();
            if (second.revision() >= renderTick) break;
            this.snapshots.removeFirst();
        }
        NetworkEntitySnapshot a = this.snapshots.getFirst();
        if (this.snapshots.size() == 1 || renderTick <= a.revision()) return a;
        var iterator = this.snapshots.iterator();
        iterator.next();
        NetworkEntitySnapshot b = iterator.next();
        double span = b.revision() - a.revision();
        double alpha = span <= 0 ? 0 : Math.clamp((renderTick - a.revision()) / span, 0, 1);
        return new NetworkEntitySnapshot(b.networkId(), b.typeId(), b.dimension(),
                Math.max(a.revision(), (long) Math.floor(renderTick)),
                lerp(a.x(), b.x(), alpha), lerp(a.y(), b.y(), alpha), lerp(a.z(), b.z(), alpha),
                lerp(a.velocityX(), b.velocityX(), alpha),
                lerp(a.velocityY(), b.velocityY(), alpha),
                lerp(a.velocityZ(), b.velocityZ(), alpha),
                lerpAngle(a.yaw(), b.yaw(), alpha),
                (float) lerp(a.pitch(), b.pitch(), alpha),
                alpha < 0.5 ? a.metadata() : b.metadata());
    }

    public int size() { return this.snapshots.size(); }
    public boolean consumeDiscontinuity() {
        boolean result = this.discontinuity;
        this.discontinuity = false;
        return result;
    }

    private static double lerp(double a, double b, double alpha) { return a + (b - a) * alpha; }

    private static float lerpAngle(float a, float b, double alpha) {
        float delta = (b - a) % 360;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        return a + delta * (float) alpha;
    }
}
