package de.skyengine.shared.world;

public record ChunkPosition(int x, int z) {
    public long packed() { return pack(this.x, this.z); }
    public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    public static ChunkPosition unpack(long packed) { return new ChunkPosition((int) (packed >> 32), (int) packed); }
}
