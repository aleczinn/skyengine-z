package de.skyengine.shared.world;

public record BlockChange(int localX, int y, int localZ, int stateId) {
    public BlockChange {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32 || y < 0 || y >= 512) {
            throw new IllegalArgumentException("Block change outside chunk column");
        }
        if (stateId < 0) throw new IllegalArgumentException("Negative block state ID");
    }

    public int packedPosition() { return localX | (localZ << 5) | (y << 10); }

    public static BlockChange fromPacked(int packed, int stateId) {
        if ((packed & ~0x7ffff) != 0) throw new IllegalArgumentException("Invalid packed block position");
        return new BlockChange(packed & 31, packed >>> 10, (packed >>> 5) & 31, stateId);
    }
}
