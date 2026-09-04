package de.skyengine.shared.network.pack;

import java.util.Arrays;
import java.util.Objects;

public record PackDescriptor(String id, String version, byte[] sha256, boolean required, long size,
                             PackType type) {
    public PackDescriptor {
        Objects.requireNonNull(id);
        Objects.requireNonNull(version);
        Objects.requireNonNull(type);
        if (id.isBlank() || version.isBlank()) throw new IllegalArgumentException("Blank pack identity");
        if (sha256 == null || sha256.length != 32) throw new IllegalArgumentException("Pack hash must be SHA-256");
        sha256 = sha256.clone();
        if (size < 0) throw new IllegalArgumentException("Negative pack size");
    }

    @Override public byte[] sha256() { return this.sha256.clone(); }

    public enum PackType { DATA, RESOURCE }
}
