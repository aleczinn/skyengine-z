package de.skyengine.game.world.chunk;

import java.util.concurrent.atomic.AtomicInteger;

public class Chunk {

    public static final int HEIGHT = 512;
    public static final int SECTIONS = HEIGHT / ChunkSection.SIZE; // 16

    public final int chunkX, chunkZ;
    private final ChunkSection[] sections = new ChunkSection[SECTIONS];

    /* volatile: written by workers, read by render thread */
    public volatile ChunkStatus status = ChunkStatus.NEW;
    private final AtomicInteger dirtySections = new AtomicInteger(0);

    public Chunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    /**
     * local coords: x/z 0-31, y 0-511
     */
    public short getBlock(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT) return 0;
        ChunkSection section = this.sections[y >> ChunkSection.SHIFT];
        if (section == null) return 0;
        return section.getBlock(x, y & ChunkSection.MASK, z);
    }

    public void setBlock(int x, int y, int z, short block) {
        if (y < 0 || y >= HEIGHT) return;
        int sectionIndex = y >> ChunkSection.SHIFT;
        ChunkSection section = this.sections[sectionIndex];
        if (section == null) {
            if (block == 0) return;
            section = this.sections[sectionIndex] = new ChunkSection();
        }
        section.setBlock(x, y & ChunkSection.MASK, z, block);
    }

    public ChunkSection getSection(int index) {
        return this.sections[index];
    }

    /**
     * Pack chunk coords into a single long map key - no object allocation for lookups
     */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public void markSectionDirty(int sectionIndex) {
        this.dirtySections.getAndUpdate(m -> m | (1 << sectionIndex));
    }

    public boolean hasDirtySections() {
        return this.dirtySections.get() != 0;
    }

    /**
     * Holt die Maske ab und setzt sie atomar auf 0.
     */
    public int consumeDirtySections() {
        return this.dirtySections.getAndSet(0);
    }
}