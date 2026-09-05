package de.skyengine.shared.world;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Nibble-packed light data; uniform sections do not allocate a payload. */
public final class LightPlane {
    public static final int SECTION_VOLUME = 32 * 32 * 32;
    public static final int PACKED_BYTES = SECTION_VOLUME / 2;

    private final Mode mode;
    private final ImmutableByteArray packedNibbles;

    public LightPlane(Mode mode, byte[] packedNibbles) {
        this(mode, packedNibbles == null
                ? ImmutableByteArray.takeOwnership(new byte[0])
                : ImmutableByteArray.copyOf(packedNibbles));
    }

    private LightPlane(Mode mode, ImmutableByteArray packedNibbles) {
        this.mode = Objects.requireNonNull(mode);
        this.packedNibbles = Objects.requireNonNull(packedNibbles);
        int expected = mode == Mode.PACKED_NIBBLES ? PACKED_BYTES : 0;
        if (packedNibbles.length() != expected) {
            throw new IllegalArgumentException("Light payload has " + packedNibbles.length()
                    + " bytes, expected " + expected + " for " + mode);
        }
    }

    public static LightPlane takeOwnership(Mode mode, byte[] packedNibbles) {
        return new LightPlane(mode, ImmutableByteArray.takeOwnership(
                packedNibbles == null ? new byte[0] : packedNibbles));
    }

    public static LightPlane shared(Mode mode, ImmutableByteArray packedNibbles) {
        return new LightPlane(mode, packedNibbles);
    }

    public Mode mode() { return this.mode; }
    public byte[] packedNibbles() { return this.packedNibbles.copy(); }
    public ImmutableByteArray packedNibblesData() { return this.packedNibbles; }
    public ByteBuffer packedNibblesView() { return this.packedNibbles.readOnlyView(); }
    public long retainedBytes() { return this.packedNibbles.retainedBytes(); }

    public enum Mode { UNIFORM_ZERO, UNIFORM_FULL, PACKED_NIBBLES }
}
