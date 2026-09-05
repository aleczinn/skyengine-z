package de.skyengine.shared.world;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable, transport-neutral state of a block entity inside a chunk column. */
public final class BlockEntitySnapshot {
    public static final int MAX_DATA_BYTES = 1024 * 1024;

    private final int localX;
    private final int y;
    private final int localZ;
    private final String typeId;
    private final ImmutableByteArray data;

    public BlockEntitySnapshot(int localX, int y, int localZ, String typeId, byte[] data) {
        this(localX, y, localZ, typeId, ImmutableByteArray.copyOf(Objects.requireNonNull(data)));
    }

    private BlockEntitySnapshot(int localX, int y, int localZ, String typeId,
                                ImmutableByteArray data) {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32 || y < 0 || y >= 512) {
            throw new IllegalArgumentException("Block entity position outside chunk");
        }
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.data = Objects.requireNonNull(data, "data");
        if (data.length() > MAX_DATA_BYTES) throw new IllegalArgumentException("Block entity payload too large");
        this.localX = localX;
        this.y = y;
        this.localZ = localZ;
    }

    /** Network decoder ownership transfer; the byte array must never be changed afterwards. */
    public static BlockEntitySnapshot takeOwnership(int localX, int y, int localZ,
                                                    String typeId, byte[] data) {
        return new BlockEntitySnapshot(localX, y, localZ, typeId,
                ImmutableByteArray.takeOwnership(Objects.requireNonNull(data)));
    }

    public int localX() { return this.localX; }
    public int y() { return this.y; }
    public int localZ() { return this.localZ; }
    public String typeId() { return this.typeId; }
    public byte[] data() { return this.data.copy(); }
    /** Compatibility accessor. It is deliberately defensive despite the historical name. */
    public byte[] dataView() { return this.data.copy(); }
    public ImmutableByteArray dataPayload() { return this.data; }
    public ByteBuffer dataBuffer() { return this.data.readOnlyView(); }
}
