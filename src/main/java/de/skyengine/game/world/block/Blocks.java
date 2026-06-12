package de.skyengine.game.world.block;

public final class Blocks {

    public static final short AIR = 0;
    public static final short STONE = 1;
    public static final short DIRT = 2;
    public static final short GRASS = 3;

    /** face: 0=top, 1=bottom, 2..5=sides. Returns texture array layer. */
    public static int getTextureLayer(short block, int face) {
        return switch (block) {
            case STONE -> 0;
            case DIRT -> 1;
            case GRASS -> (face == 0 ? 3 : (face == 1 ? 1 : 2)); // top=grass, bottom=dirt, side=grass_side
            default -> 0;
        };
    }

    public static boolean isOpaque(short block) {
        return block != AIR;
    }
}