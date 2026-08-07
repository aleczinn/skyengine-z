package de.skyengine.game.world.block.behavior;

import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.redstone.RedstonePower;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RailShape;
import de.skyengine.game.world.block.state.Property;

/** Verbindungs-, Steigungs- und Redstone-Logik der vier Vanilla-Schienenarten. */
public final class RailBehavior implements BlockBehavior {

    private static final int POWER_RANGE = 8;
    private static final int DETECTOR_RECHECK_TICKS = 20;

    public enum Kind {
        NORMAL, POWERED, DETECTOR, ACTIVATOR;

        public static Kind byName(String value) {
            if (value == null) return NORMAL;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("Unbekannte Schienenart: " + value);
            }
        }

        public boolean straightOnly() { return this != NORMAL; }
        public boolean hasPoweredState() { return this != NORMAL; }
    }

    private final Kind kind;
    private final Property<RailShape> shapeProperty;

    public RailBehavior(Kind kind, Property<RailShape> shapeProperty) {
        this.kind = kind;
        this.shapeProperty = shapeProperty;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        RailShape initial = Direction.fromYaw(ctx.playerYaw()).axis() == Direction.Axis.X
                ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        state = state.with(this.shapeProperty, initial);
        return this.calculateShape(ctx.world(), ctx.x(), ctx.y(), ctx.z(), state,
                this.isDirectlyPowered(ctx.world(), ctx.x(), ctx.y(), ctx.z()));
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        this.refreshNeighbors(world, x, y, z);
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        boolean direct = this.isDirectlyPowered(world, x, y, z);
        BlockState shaped = this.calculateShape(world, x, y, z, state, direct);
        if (this.kind == Kind.POWERED || this.kind == Kind.ACTIVATOR) {
            boolean powered = direct || this.findPoweredRailSignal(world, x, y, z, shaped, 0);
            shaped = shaped.with(Properties.POWERED, powered);
        } else if (this.kind == Kind.DETECTOR) {
            shaped = shaped.with(Properties.POWERED,
                    world.hasMinecartAtRail(x, y, z));
            if (shaped.get(Properties.POWERED)) {
                world.scheduleTickEarlier(x, y, z, DETECTOR_RECHECK_TICKS);
            }
        }
        return shaped;
    }

    @Override
    public void onStateChangedByNeighborUpdate(World world, int x, int y, int z,
                                               BlockState oldState, BlockState newState) {
        if (oldState.get(this.shapeProperty) != newState.get(this.shapeProperty)) {
            this.refreshNeighbors(world, x, y, z);
        }
        if (this.kind != Kind.NORMAL
                && oldState.get(Properties.POWERED) != newState.get(Properties.POWERED)) {
            this.refreshNeighbors(world, x, y, z);
        }
    }

    @Override
    public void onRemoved(World world, int x, int y, int z, BlockState oldState, BlockState newState) {
        this.refreshNeighbors(world, x, y, z);
    }

    @Override
    public void onEntityInside(World world, int x, int y, int z, BlockState state,
                               de.skyengine.game.entity.Entity entity) {
        if (this.kind != Kind.DETECTOR || !(entity instanceof MinecartEntity)
                || state.get(Properties.POWERED)) return;
        world.setBlockWithShapeUpdates(x, y, z, state.with(Properties.POWERED, true).getId());
        world.scheduleTickEarlier(x, y, z, DETECTOR_RECHECK_TICKS);
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        if (this.kind != Kind.DETECTOR) return;
        boolean occupied = world.hasMinecartAtRail(x, y, z);
        if (state.get(Properties.POWERED) != occupied) {
            world.setBlockWithShapeUpdates(x, y, z,
                    state.with(Properties.POWERED, occupied).getId());
        }
        if (occupied) world.scheduleTickEarlier(x, y, z, DETECTOR_RECHECK_TICKS);
    }

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return this.kind == Kind.DETECTOR && state.get(Properties.POWERED) ? 15 : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return this.kind == Kind.DETECTOR && state.get(Properties.POWERED) && side == Direction.DOWN ? 15 : 0;
    }

    @Override
    public boolean isRedstoneSignalSource() {
        return this.kind == Kind.DETECTOR;
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return this.kind == Kind.DETECTOR;
    }

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return this.kind != Kind.NORMAL;
    }

    private boolean isDirectlyPowered(World world, int x, int y, int z) {
        return RedstonePower.isReceiving(world, x, y, z);
    }

    private BlockState calculateShape(World world, int x, int y, int z, BlockState state,
                                      boolean powered) {
        boolean north = canConnectTo(world, x, y, z, Direction.NORTH);
        boolean south = canConnectTo(world, x, y, z, Direction.SOUTH);
        boolean west = canConnectTo(world, x, y, z, Direction.WEST);
        boolean east = canConnectTo(world, x, y, z, Direction.EAST);

        RailShape shape = null;
        if ((north || south) && !west && !east) shape = RailShape.NORTH_SOUTH;
        if ((west || east) && !north && !south) shape = RailShape.EAST_WEST;
        if (!this.kind.straightOnly()) {
            if (south && east && !north && !west) shape = RailShape.SOUTH_EAST;
            if (south && west && !north && !east) shape = RailShape.SOUTH_WEST;
            if (north && west && !south && !east) shape = RailShape.NORTH_WEST;
            if (north && east && !south && !west) shape = RailShape.NORTH_EAST;
        }
        if (shape == null) {
            RailShape old = state.get(this.shapeProperty);
            shape = old == RailShape.EAST_WEST || old == RailShape.ASCENDING_EAST
                    || old == RailShape.ASCENDING_WEST ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
            if ((north || south) && !(west || east)) shape = RailShape.NORTH_SOUTH;
            if ((west || east) && !(north || south)) shape = RailShape.EAST_WEST;
            if (!this.kind.straightOnly()) {
                if (powered) {
                    if (south && east) shape = RailShape.SOUTH_EAST;
                    else if (south && west) shape = RailShape.SOUTH_WEST;
                    else if (north && east) shape = RailShape.NORTH_EAST;
                    else if (north && west) shape = RailShape.NORTH_WEST;
                } else {
                    if (north && west) shape = RailShape.NORTH_WEST;
                    else if (north && east) shape = RailShape.NORTH_EAST;
                    else if (south && west) shape = RailShape.SOUTH_WEST;
                    else if (south && east) shape = RailShape.SOUTH_EAST;
                }
            }
        }

        if (shape == RailShape.NORTH_SOUTH) {
            if (isRail(world, x, y + 1, z - 1)) shape = RailShape.ASCENDING_NORTH;
            else if (isRail(world, x, y + 1, z + 1)) shape = RailShape.ASCENDING_SOUTH;
        } else if (shape == RailShape.EAST_WEST) {
            if (isRail(world, x + 1, y + 1, z)) shape = RailShape.ASCENDING_EAST;
            else if (isRail(world, x - 1, y + 1, z)) shape = RailShape.ASCENDING_WEST;
        }
        return state.with(this.shapeProperty, shape);
    }

    private boolean findPoweredRailSignal(World world, int x, int y, int z,
                                          BlockState state, int depth) {
        if (depth >= POWER_RANGE) return false;
        for (Direction direction : endpoints(state.get(this.shapeProperty))) {
            int nx = x + direction.offsetX();
            int nz = z + direction.offsetZ();
            int ny = y;
            RailShape shape = state.get(this.shapeProperty);
            if (ascendingToward(shape, direction)) ny++;

            if (this.isPoweredContinuation(world, nx, ny, nz, state, shape, depth)) return true;
            if (ny == y && this.isPoweredContinuation(world, nx, y - 1, nz,
                    state, shape, depth)) return true;
        }
        return false;
    }

    private boolean isPoweredContinuation(World world, int x, int y, int z,
                                          BlockState origin, RailShape originShape, int depth) {
        BlockState next = railAt(world, x, y, z);
        if (next == null || next.getBlock() != origin.getBlock()
                || !next.get(Properties.POWERED)
                || axis(shape(next)) != axis(originShape)) return false;
        return this.isDirectlyPowered(world, x, y, z)
                || this.findPoweredRailSignal(world, x, y, z, next, depth + 1);
    }

    private void refreshNeighbors(World world, int x, int y, int z) {
        for (Direction d : Direction.horizontalValues()) {
            world.updateBlockStateAt(x + d.offsetX(), y, z + d.offsetZ());
            world.updateBlockStateAt(x + d.offsetX(), y + 1, z + d.offsetZ());
            world.updateBlockStateAt(x + d.offsetX(), y - 1, z + d.offsetZ());
        }
    }

    private static boolean hasRail(World world, int x, int y, int z) {
        return isRail(world, x, y, z) || isRail(world, x, y + 1, z) || isRail(world, x, y - 1, z);
    }

    /** Vanillas RailState.canConnectTo: vorhandene Verbindung oder ein Nachbar mit freiem Ende. */
    private static boolean canConnectTo(World world, int x, int y, int z, Direction toward) {
        int nx = x + toward.offsetX(), nz = z + toward.offsetZ();
        BlockState neighbor = railAt(world, nx, y, nz);
        int ny = y;
        if (neighbor == null) { neighbor = railAt(world, nx, y + 1, nz); ny = y + 1; }
        if (neighbor == null) { neighbor = railAt(world, nx, y - 1, nz); ny = y - 1; }
        if (neighbor == null) return false;

        Direction back = toward.opposite();
        for (Direction endpoint : endpoints(shape(neighbor))) {
            if (endpoint == back) return true;
        }

        int connected = 0;
        for (Direction endpoint : endpoints(shape(neighbor))) {
            int ex = nx + endpoint.offsetX(), ez = nz + endpoint.offsetZ();
            int ey = ny + (ascendingToward(shape(neighbor), endpoint) ? 1 : 0);
            if (hasRail(world, ex, ey, ez)) connected++;
        }
        return connected < 2;
    }

    public static boolean isRail(World world, int x, int y, int z) {
        return railAt(world, x, y, z) != null;
    }

    public static BlockState railAt(World world, int x, int y, int z) {
        BlockState state = de.skyengine.game.world.block.Blocks.getState(world.getBlock(x, y, z));
        return state.getValues().containsKey(Properties.RAIL_SHAPE)
                || state.getValues().containsKey(Properties.STRAIGHT_RAIL_SHAPE) ? state : null;
    }

    public static RailShape shape(BlockState state) {
        if (state.getValues().containsKey(Properties.RAIL_SHAPE)) return state.get(Properties.RAIL_SHAPE);
        return state.get(Properties.STRAIGHT_RAIL_SHAPE);
    }

    public static Direction.Axis axis(RailShape shape) {
        return switch (shape) {
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> Direction.Axis.X;
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> Direction.Axis.Z;
            default -> null;
        };
    }

    public static Direction[] endpoints(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> new Direction[]{Direction.NORTH, Direction.SOUTH};
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new Direction[]{Direction.WEST, Direction.EAST};
            case SOUTH_EAST -> new Direction[]{Direction.SOUTH, Direction.EAST};
            case SOUTH_WEST -> new Direction[]{Direction.SOUTH, Direction.WEST};
            case NORTH_WEST -> new Direction[]{Direction.NORTH, Direction.WEST};
            case NORTH_EAST -> new Direction[]{Direction.NORTH, Direction.EAST};
        };
    }

    public static boolean ascendingToward(RailShape shape, Direction direction) {
        return shape == RailShape.ASCENDING_EAST && direction == Direction.EAST
                || shape == RailShape.ASCENDING_WEST && direction == Direction.WEST
                || shape == RailShape.ASCENDING_NORTH && direction == Direction.NORTH
                || shape == RailShape.ASCENDING_SOUTH && direction == Direction.SOUTH;
    }
}
