package de.skyengine.shared.world;

import java.util.Objects;
import java.nio.ByteBuffer;

/** Nibble-packed light data; uniform sections do not allocate a payload. */
public record LightPlane(Mode mode, byte[] packedNibbles) {
    public static final int SECTION_VOLUME = 32 * 32 * 32;
    public static final int PACKED_BYTES = SECTION_VOLUME / 2;

    public LightPlane {
        Objects.requireNonNull(mode);
        packedNibbles = packedNibbles == null ? new byte[0] : packedNibbles.clone();
        int expected = mode == Mode.PACKED_NIBBLES ? PACKED_BYTES : 0;
        if (packedNibbles.length != expected) {
            throw new IllegalArgumentException("Light payload has " + packedNibbles.length
                    + " bytes, expected " + expected + " for " + mode);
        }
    }

    @Override public byte[] packedNibbles() { return this.packedNibbles.clone(); }
    /** Read-only bulk view used by codecs without exposing mutable snapshot storage. */
    public ByteBuffer packedNibblesView() { return ByteBuffer.wrap(this.packedNibbles).asReadOnlyBuffer(); }

    public enum Mode { UNIFORM_ZERO, UNIFORM_FULL, PACKED_NIBBLES }
}
