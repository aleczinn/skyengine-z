package de.skyengine.game.world.chunk;

import de.skyengine.game.world.chunk.palette.PalettedContainer;

public class ChunkSection {

    public static final int SIZE = 32;
    public static final int SHIFT = 5;
    public static final int MASK = SIZE - 1;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    /* Lazily allocated - leere Sektionen (reine Luft) kosten nichts. Sonst paletten-
       komprimiert: typische Chunks brauchen nur wenige Bit pro Block statt der vollen ID-Breite. */
    private PalettedContainer container;

    public ChunkSection() {}

    /** Rebuild aus persistierten Daten (Chunk-Load). */
    public ChunkSection(PalettedContainer container) {
        this.container = container;
    }

    public int getBlock(int x, int y, int z) {
        if (this.container == null) return 0;
        return this.container.get((y << (SHIFT * 2)) | (z << SHIFT) | x);
    }

    public void setBlock(int x, int y, int z, int block) {
        if (this.container == null) {
            if (block == 0) return;
            this.container = new PalettedContainer(VOLUME, 0);
        }
        this.container.set((y << (SHIFT * 2)) | (z << SHIFT) | x, block);
    }

    public boolean isEmpty() {
        return this.container == null || this.container.isEmpty();
    }

    public PalettedContainer container() {
        return this.container;
    }
}
