package de.skyengine.game.world.block;

/** Unveränderliche Block-Position in Weltkoordinaten. */
public record BlockPos(int x, int y, int z) {

    public BlockPos offset(Direction d) {
        return new BlockPos(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
    }

    /* Long-Packung 26 Bit x | 26 Bit z | 12 Bit y — dasselbe Layout wie in
       ScheduledTickQueue/ChunkRenderer.sectionKey, hier als geteilte Utility. */

    public static long asLong(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(long key) {
        return (int) (key >> 38); // arithmetischer Shift = Vorzeichenerweiterung
    }

    public static int unpackZ(long key) {
        return ((int) ((key >> 12) & 0x3FFFFFF) << 6) >> 6;
    }

    public static int unpackY(long key) {
        return (int) (key & 0xFFF);
    }
}
