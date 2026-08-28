package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.Direction;

/** Machine face relative to its configured front. */
public enum RelativeSide {
    FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM;

    /** Exact mapping used by Mekanism's RelativeSide#getDirection. */
    public Direction toWorld(Direction front) {
        return switch (this) {
            case FRONT -> front;
            case BACK -> front.opposite();
            case LEFT -> front.axis() == Direction.Axis.Y ? Direction.EAST : front.rotateYCW();
            case RIGHT -> front.axis() == Direction.Axis.Y ? Direction.WEST : front.rotateYCCW();
            case TOP -> switch (front) {
                case DOWN -> Direction.NORTH;
                case UP -> Direction.SOUTH;
                default -> Direction.UP;
            };
            case BOTTOM -> switch (front) {
                case DOWN -> Direction.SOUTH;
                case UP -> Direction.NORTH;
                default -> Direction.DOWN;
            };
        };
    }

    public static RelativeSide fromWorld(Direction front, Direction side) {
        for (RelativeSide relative : values()) {
            if (relative.toWorld(front) == side) return relative;
        }
        throw new IllegalArgumentException("No relative side for " + front + " / " + side);
    }
}
