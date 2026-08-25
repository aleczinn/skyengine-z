package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ChestType;
import de.skyengine.game.world.block.state.DoorHinge;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RailShape;
import de.skyengine.game.world.block.state.StairShape;

/** Koordinaten- und Blockstate-Transformation; Spiegelung wird vor Rotation angewandt. */
public record StructureTransform(Rotation rotation, Mirror mirror) {
    public enum Rotation { NONE, CLOCKWISE_90, CLOCKWISE_180, CLOCKWISE_270 }
    public enum Mirror { NONE, LEFT_RIGHT, FRONT_BACK }
    public static final StructureTransform IDENTITY = new StructureTransform(Rotation.NONE, Mirror.NONE);

    public int transformedX(int x, int z) {
        int mx = mirror == Mirror.FRONT_BACK ? -x : x;
        int mz = mirror == Mirror.LEFT_RIGHT ? -z : z;
        return switch (rotation) {
            case NONE -> mx;
            case CLOCKWISE_90 -> -mz;
            case CLOCKWISE_180 -> -mx;
            case CLOCKWISE_270 -> mz;
        };
    }

    public int transformedZ(int x, int z) {
        int mx = mirror == Mirror.FRONT_BACK ? -x : x;
        int mz = mirror == Mirror.LEFT_RIGHT ? -z : z;
        return switch (rotation) {
            case NONE -> mz;
            case CLOCKWISE_90 -> mx;
            case CLOCKWISE_180 -> -mz;
            case CLOCKWISE_270 -> -mx;
        };
    }

    public Direction direction(Direction direction) {
        Direction result = switch (mirror) {
            case NONE -> direction;
            case LEFT_RIGHT -> direction == Direction.NORTH ? Direction.SOUTH
                    : direction == Direction.SOUTH ? Direction.NORTH : direction;
            case FRONT_BACK -> direction == Direction.WEST ? Direction.EAST
                    : direction == Direction.EAST ? Direction.WEST : direction;
        };
        int turns = rotation.ordinal();
        for (int i = 0; i < turns; i++) result = result.rotateYCW();
        return result;
    }

    public BlockState state(BlockState original) {
        BlockState state = original;
        if (state.getValues().containsKey(Properties.FACING)) {
            state = state.with(Properties.FACING, direction(state.get(Properties.FACING)));
        }
        if (state.getValues().containsKey(Properties.FACING_ALL)) {
            state = state.with(Properties.FACING_ALL, direction(state.get(Properties.FACING_ALL)));
        }
        if (state.getValues().containsKey(Properties.AXIS)) {
            state = state.with(Properties.AXIS, axis(state.get(Properties.AXIS)));
        }
        if (state.getValues().containsKey(Properties.HORIZONTAL_AXIS)) {
            state = state.with(Properties.HORIZONTAL_AXIS, axis(state.get(Properties.HORIZONTAL_AXIS)));
        }
        state = connections(state);
        if (mirror != Mirror.NONE && state.getValues().containsKey(Properties.STAIR_SHAPE)) {
            StairShape shape = state.get(Properties.STAIR_SHAPE);
            state = state.with(Properties.STAIR_SHAPE, switch (shape) {
                case INNER_LEFT -> StairShape.INNER_RIGHT;
                case INNER_RIGHT -> StairShape.INNER_LEFT;
                case OUTER_LEFT -> StairShape.OUTER_RIGHT;
                case OUTER_RIGHT -> StairShape.OUTER_LEFT;
                default -> shape;
            });
        }
        if (mirror != Mirror.NONE && state.getValues().containsKey(Properties.HINGE)) {
            state = state.with(Properties.HINGE,
                    state.get(Properties.HINGE) == DoorHinge.LEFT ? DoorHinge.RIGHT : DoorHinge.LEFT);
        }
        if (mirror != Mirror.NONE && state.getValues().containsKey(Properties.CHEST_TYPE)) {
            ChestType type = state.get(Properties.CHEST_TYPE);
            if (type == ChestType.LEFT || type == ChestType.RIGHT) {
                state = state.with(Properties.CHEST_TYPE, type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT);
            }
        }
        if (state.getValues().containsKey(Properties.RAIL_SHAPE)) {
            state = state.with(Properties.RAIL_SHAPE, rail(state.get(Properties.RAIL_SHAPE)));
        } else if (state.getValues().containsKey(Properties.STRAIGHT_RAIL_SHAPE)) {
            state = state.with(Properties.STRAIGHT_RAIL_SHAPE, rail(state.get(Properties.STRAIGHT_RAIL_SHAPE)));
        }
        return state;
    }

    private Direction.Axis axis(Direction.Axis axis) {
        if (axis == Direction.Axis.Y) return axis;
        Direction direction = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        return direction(direction).axis();
    }

    private BlockState connections(BlockState state) {
        Direction[] dirs = Direction.horizontalValues();
        boolean[] values = {
                state.getValues().containsKey(Properties.NORTH) && state.get(Properties.NORTH),
                state.getValues().containsKey(Properties.EAST) && state.get(Properties.EAST),
                state.getValues().containsKey(Properties.SOUTH) && state.get(Properties.SOUTH),
                state.getValues().containsKey(Properties.WEST) && state.get(Properties.WEST)
        };
        if (state.getValues().containsKey(Properties.NORTH)) state = state.with(Properties.NORTH, false);
        if (state.getValues().containsKey(Properties.EAST)) state = state.with(Properties.EAST, false);
        if (state.getValues().containsKey(Properties.SOUTH)) state = state.with(Properties.SOUTH, false);
        if (state.getValues().containsKey(Properties.WEST)) state = state.with(Properties.WEST, false);
        for (int i = 0; i < 4; i++) if (values[i]) state = state.with(Properties.connection(direction(dirs[i])), true);
        return state;
    }

    private RailShape rail(RailShape shape) {
        RailShape result = shape;
        if (mirror != Mirror.NONE) result = mirrorRail(result, mirror);
        for (int i = 0; i < rotation.ordinal(); i++) result = rotateRail(result);
        return result;
    }

    private static RailShape rotateRail(RailShape s) {
        return switch (s) {
            case NORTH_SOUTH -> RailShape.EAST_WEST; case EAST_WEST -> RailShape.NORTH_SOUTH;
            case ASCENDING_EAST -> RailShape.ASCENDING_SOUTH; case ASCENDING_SOUTH -> RailShape.ASCENDING_WEST;
            case ASCENDING_WEST -> RailShape.ASCENDING_NORTH; case ASCENDING_NORTH -> RailShape.ASCENDING_EAST;
            case SOUTH_EAST -> RailShape.SOUTH_WEST; case SOUTH_WEST -> RailShape.NORTH_WEST;
            case NORTH_WEST -> RailShape.NORTH_EAST; case NORTH_EAST -> RailShape.SOUTH_EAST;
        };
    }

    private static RailShape mirrorRail(RailShape s, Mirror mirror) {
        if (mirror == Mirror.LEFT_RIGHT) return switch (s) {
            case ASCENDING_NORTH -> RailShape.ASCENDING_SOUTH; case ASCENDING_SOUTH -> RailShape.ASCENDING_NORTH;
            case SOUTH_EAST -> RailShape.NORTH_EAST; case SOUTH_WEST -> RailShape.NORTH_WEST;
            case NORTH_WEST -> RailShape.SOUTH_WEST; case NORTH_EAST -> RailShape.SOUTH_EAST; default -> s;
        };
        return switch (s) {
            case ASCENDING_EAST -> RailShape.ASCENDING_WEST; case ASCENDING_WEST -> RailShape.ASCENDING_EAST;
            case SOUTH_EAST -> RailShape.SOUTH_WEST; case SOUTH_WEST -> RailShape.SOUTH_EAST;
            case NORTH_WEST -> RailShape.NORTH_EAST; case NORTH_EAST -> RailShape.NORTH_WEST; default -> s;
        };
    }
}
