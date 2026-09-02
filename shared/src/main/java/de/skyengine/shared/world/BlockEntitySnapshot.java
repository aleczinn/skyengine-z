package de.skyengine.shared.world;

import java.util.Objects;

/** Immutable, transport-neutral state of a block entity inside a chunk column. */
public record BlockEntitySnapshot(int localX, int y, int localZ, String typeId, byte[] data) {
    public static final int MAX_DATA_BYTES = 1024 * 1024;

    public BlockEntitySnapshot {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32 || y < 0 || y >= 512) {
            throw new IllegalArgumentException("Block entity position outside chunk");
        }
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(data, "data");
        if (data.length > MAX_DATA_BYTES) throw new IllegalArgumentException("Block entity payload too large");
        data = data.clone();
    }

    @Override public byte[] data() { return this.data.clone(); }

    public byte[] dataView() { return this.data; }
}
