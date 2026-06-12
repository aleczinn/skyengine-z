package de.skyengine.game.world.chunk;

public class ChunkSection {

    public static final int SIZE = 32;
    public static final int SHIFT = 5;
    public static final int MASK = SIZE - 1;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    /* Lazily allocated - empty sections (pure air/sky) cost nothing.
       short[] now, palette compression later. */
    private short[] blocks;
    private int nonAirCount = 0;

    public short getBlock(int x, int y, int z) {
        if (this.blocks == null) return 0;
        return this.blocks[(y << (SHIFT * 2)) | (z << SHIFT) | x];
    }

    public void setBlock(int x, int y, int z, short block) {
        int index = (y << (SHIFT * 2)) | (z << SHIFT) | x;
        if (this.blocks == null) {
            if (block == 0) return;
            this.blocks = new short[VOLUME];
        }

        short old = this.blocks[index];
        if (old == block) return;
        if (old == 0) this.nonAirCount++;
        if (block == 0) this.nonAirCount--;
        this.blocks[index] = block;
    }

    public boolean isEmpty() {
        return this.nonAirCount == 0;
    }
}