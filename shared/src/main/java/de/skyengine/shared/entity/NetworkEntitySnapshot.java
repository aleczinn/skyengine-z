package de.skyengine.shared.entity;

import java.util.Objects;

public record NetworkEntitySnapshot(int networkId, int typeId, String dimension, long revision,
                                    double x, double y, double z, double velocityX, double velocityY,
                                    double velocityZ, float yaw, float pitch, byte[] metadata) {
    public static final int MAX_METADATA_BYTES = 64 * 1024;
    public NetworkEntitySnapshot {
        if (networkId <= 0 || typeId < 0 || revision < 0) throw new IllegalArgumentException("Invalid entity IDs");
        Objects.requireNonNull(dimension);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Invalid entity transform");
        metadata = metadata == null ? new byte[0] : metadata.clone();
        if (metadata.length > MAX_METADATA_BYTES) throw new IllegalArgumentException("Entity metadata too large");
    }
    @Override public byte[] metadata() { return this.metadata.clone(); }
}
