package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.Direction;

/** Machine face relative to its configured front. */
public enum RelativeSide {
    FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM;

    public static RelativeSide fromWorld(Direction front, Direction side) {
        if (side == front) return FRONT;
        if (side == front.opposite()) return BACK;
        if (front.axis() != Direction.Axis.Y) {
            if (side == Direction.UP) return TOP;
            if (side == Direction.DOWN) return BOTTOM;
            return side == front.rotateYCCW() ? LEFT : RIGHT;
        }
        if (side == Direction.WEST) return LEFT;
        if (side == Direction.EAST) return RIGHT;
        if (front == Direction.UP) return side == Direction.NORTH ? TOP : BOTTOM;
        return side == Direction.SOUTH ? TOP : BOTTOM;
    }
}
