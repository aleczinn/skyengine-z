package de.skyengine.game.world.structure;

/** Inklusive ganzzahlige Begrenzung einer Struktur. */
public record StructureBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public StructureBounds {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("Leere Struktur-Bounding-Box");
        }
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }
    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }
}
