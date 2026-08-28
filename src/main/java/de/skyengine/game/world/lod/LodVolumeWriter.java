package de.skyengine.game.world.lod;

@FunctionalInterface
public interface LodVolumeWriter {
    /** Schreibt eine lokale Zelle des angeforderten 32³-Knotens. */
    void set(int x, int y, int z, long voxel);
}
