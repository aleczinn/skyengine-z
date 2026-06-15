package de.skyengine.game.world.block;

/** Unveränderliche Block-Position in Weltkoordinaten. */
public record BlockPos(int x, int y, int z) {

    public BlockPos offset(Direction d) {
        return new BlockPos(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
    }
}
