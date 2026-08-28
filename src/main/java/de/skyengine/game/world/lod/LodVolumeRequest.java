package de.skyengine.game.world.lod;

/** Weltkoordinaten-Vertrag fuer analytisch erzeugte 32³-LOD-Knoten. */
public record LodVolumeRequest(int nodeX, int nodeY, int nodeZ, int level) {
    public LodVolumeRequest {
        if (level < 0 || level > LodVoxelSection.MAX_LEVEL) throw new IllegalArgumentException("LOD-Level: " + level);
    }
    public int cellSize() { return 1 << this.level; }
    public int originX() { return this.nodeX * LodVoxelSection.SIZE * cellSize(); }
    public int originY() { return this.nodeY * LodVoxelSection.SIZE * cellSize(); }
    public int originZ() { return this.nodeZ * LodVoxelSection.SIZE * cellSize(); }
    public int extent() { return LodVoxelSection.SIZE * cellSize(); }
}
