package de.skyengine.game.world.redstone;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.SupportBehavior;
import de.skyengine.game.world.block.behavior.TrapdoorBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RedstoneSide;
import de.skyengine.utils.collect.LongIntMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Vanillas normaler {@code DefaultRedstoneWireEvaluator}: Ein Weckruf berechnet ausschließlich
 * die betroffene Staubzelle. Ändert sie sich, laufen die daraus entstehenden Nachbar-Updates
 * verschachtelt in der Reihenfolge von {@code NeighborUpdater.UPDATE_ORDER} weiter.
 *
 * <p>Das ist bewusst kein globaler Netz-Fixpunkt. Dadurch bleiben Update-Reihenfolge und die
 * daraus entstehenden Java-Edition-Eigenheiten erhalten. Der explizite Task-Stack entspricht
 * Vanillas {@code CollectingNeighborUpdater} und verhindert einen Java-Stacküberlauf bei langen
 * Leitungen.
 */
public final class RedstoneWireNetwork {

    /** Aktuelle Java-Edition-Reihenfolge: WEST, EAST, DOWN, UP, NORTH, SOUTH. */
    private static final Direction[] UPDATE_ORDER = {
            Direction.WEST, Direction.EAST, Direction.DOWN,
            Direction.UP, Direction.NORTH, Direction.SOUTH
    };

    /** Ein Update-Kontext je Thread; unabhängige Welten unterdrücken einander nicht. */
    private static final ThreadLocal<UpdateContext> ACTIVE = new ThreadLocal<>();

    public static void update(World world, int x, int y, int z) {
        update(world, x, y, z, null);
    }

    /**
     * Chunk-Kanten-Abgleich. Anders als der frühere Komponentenlöser darf Vanilla einzelne
     * Zellen nicht überspringen; {@code visited} dient nur noch der Statistik des Scan-Aufrufers.
     */
    public static void updateOncePerComponent(World world, int x, int y, int z, LongIntMap visited) {
        update(world, x, y, z, visited);
    }

    private static void update(World world, int x, int y, int z, LongIntMap visited) {
        if (!RedstonePower.isWire(Blocks.getState(world.getBlock(x, y, z)))) return;
        if (visited != null) visited.put(BlockPos.asLong(x, y, z), 1);
        world.getSimulationTelemetry().recordWireWake();

        UpdateContext active = ACTIVE.get();
        if (active != null && active.world == world) {
            active.stack.push(new EvaluateTask(x, y, z));
            return;
        }

        UpdateContext context = new UpdateContext(world);
        UpdateContext previous = ACTIVE.get();
        ACTIVE.set(context);
        context.stack.push(new EvaluateTask(x, y, z));
        try {
            while (!context.stack.isEmpty()) context.stack.pop().run(context);
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    private interface UpdateTask {
        void run(UpdateContext context);
    }

    private static final class UpdateContext {
        private final World world;
        private final ArrayDeque<UpdateTask> stack = new ArrayDeque<>();

        private UpdateContext(World world) {
            this.world = world;
        }
    }

    /** Berechnet genau eine Staubzelle, entsprechend DefaultRedstoneWireEvaluator. */
    private record EvaluateTask(int x, int y, int z) implements UpdateTask {
        @Override
        public void run(UpdateContext context) {
            World world = context.world;
            BlockState current = Blocks.getState(world.getBlock(this.x, this.y, this.z));
            if (!RedstonePower.isWire(current)) return;
            long started = world.getSimulationTelemetry().beginRedstoneTiming();

            int external = RedstonePower.receivedPowerIgnoringWire(world, this.x, this.y, this.z);
            int incoming = external == 15 ? 0 : incomingWirePower(world, this.x, this.y, this.z);
            int power = Math.max(external, Math.max(0, incoming - 1));
            BlockState updated = updateShape(world, this.x, this.y, this.z, current)
                    .with(Properties.POWER, power);
            if (updated == current) {
                world.getSimulationTelemetry().recordWireSolve(
                        started, this.x, this.y, this.z, 1, 0, 0, false);
                return;
            }

            world.setBlock(this.x, this.y, this.z, updated.getId(), false);
            List<WirePos> notificationCenters = notificationCenters(this.x, this.y, this.z);
            /* Vanillas Collector verarbeitet zuerst das zuerst hinzugefügte Multi-Update.
               Stack deshalb rückwärts befüllen. */
            for (int i = notificationCenters.size() - 1; i >= 0; i--) {
                context.stack.push(new NotifyTask(notificationCenters.get(i), 0));
            }
            world.getSimulationTelemetry().recordWireSolve(
                    started, this.x, this.y, this.z, 1, 1,
                    notificationCenters.size() * UPDATE_ORDER.length, false);
        }
    }

    /** Ein pausierbares updateNeighborsAt; verschachtelte Updates laufen vor der nächsten Richtung. */
    private record NotifyTask(WirePos center, int directionIndex) implements UpdateTask {
        @Override
        public void run(UpdateContext context) {
            Direction direction = UPDATE_ORDER[this.directionIndex];
            if (this.directionIndex + 1 < UPDATE_ORDER.length) {
                context.stack.push(new NotifyTask(this.center, this.directionIndex + 1));
            }
            context.world.updateBlockStateAt(
                    this.center.x + direction.offsetX(),
                    this.center.y + direction.offsetY(),
                    this.center.z + direction.offsetZ());
        }
    }

    /**
     * HashSet absichtlich wie Vanilla. WirePos verwendet denselben Hash wie Vec3i/BlockPos,
     * damit auch die Iterationsreihenfolge der sieben Update-Zentren übereinstimmt.
     */
    private static List<WirePos> notificationCenters(int x, int y, int z) {
        Set<WirePos> positions = new HashSet<>();
        positions.add(new WirePos(x, y, z));
        /* Direction.values in Vanilla: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
        positions.add(new WirePos(x, y - 1, z));
        positions.add(new WirePos(x, y + 1, z));
        positions.add(new WirePos(x, y, z - 1));
        positions.add(new WirePos(x, y, z + 1));
        positions.add(new WirePos(x - 1, y, z));
        positions.add(new WirePos(x + 1, y, z));
        return new ArrayList<>(positions);
    }

    private record WirePos(int x, int y, int z) {
        @Override
        public int hashCode() {
            return (this.y + this.z * 31) * 31 + this.x;
        }
    }

    /** Höchstes benachbartes Staubsignal nach Vanillas getIncomingWireSignal. */
    private static int incomingWirePower(World world, int x, int y, int z) {
        int power = 0;
        boolean aboveConductive = Blocks.getState(world.getBlock(x, y + 1, z)).isRedstoneConductor();
        for (Direction direction : Direction.horizontalValues()) {
            int nx = x + direction.offsetX(), nz = z + direction.offsetZ();
            BlockState neighbor = Blocks.getState(world.getBlock(nx, y, nz));
            power = Math.max(power, wirePower(neighbor));
            if (neighbor.isRedstoneConductor()) {
                if (!aboveConductive) {
                    power = Math.max(power, wirePower(Blocks.getState(world.getBlock(nx, y + 1, nz))));
                }
            } else {
                power = Math.max(power, wirePower(Blocks.getState(world.getBlock(nx, y - 1, nz))));
            }
        }
        return power;
    }

    private static int wirePower(BlockState state) {
        return RedstonePower.isWire(state) ? state.get(Properties.POWER) : 0;
    }

    private static BlockState updateShape(World world, int x, int y, int z, BlockState current) {
        RedstoneSide north = sideShape(world, x, y, z, Direction.NORTH);
        RedstoneSide east = sideShape(world, x, y, z, Direction.EAST);
        RedstoneSide south = sideShape(world, x, y, z, Direction.SOUTH);
        RedstoneSide west = sideShape(world, x, y, z, Direction.WEST);
        boolean cn = north.isConnected(), ce = east.isConnected();
        boolean cs = south.isConnected(), cw = west.isConnected();
        if (!(isDot(current) && !cn && !ce && !cs && !cw)) {
            boolean noNorthSouth = !cn && !cs;
            boolean noEastWest = !ce && !cw;
            if (!cw && noNorthSouth) west = RedstoneSide.SIDE;
            if (!ce && noNorthSouth) east = RedstoneSide.SIDE;
            if (!cn && noEastWest) north = RedstoneSide.SIDE;
            if (!cs && noEastWest) south = RedstoneSide.SIDE;
        }
        return current.with(Properties.WIRE_NORTH, north)
                .with(Properties.WIRE_EAST, east)
                .with(Properties.WIRE_SOUTH, south)
                .with(Properties.WIRE_WEST, west);
    }

    public static boolean isDot(BlockState state) {
        for (Direction direction : Direction.horizontalValues()) {
            if (state.get(Properties.wireSide(direction)).isConnected()) return false;
        }
        return true;
    }

    public static boolean isCross(BlockState state) {
        for (Direction direction : Direction.horizontalValues()) {
            if (state.get(Properties.wireSide(direction)) != RedstoneSide.SIDE) return false;
        }
        return true;
    }

    public static BlockState toCross(BlockState state) {
        for (Direction direction : Direction.horizontalValues()) {
            state = state.with(Properties.wireSide(direction), RedstoneSide.SIDE);
        }
        return state;
    }

    public static BlockState toDot(BlockState state) {
        for (Direction direction : Direction.horizontalValues()) {
            state = state.with(Properties.wireSide(direction), RedstoneSide.NONE);
        }
        return state;
    }

    private static RedstoneSide sideShape(World world, int x, int y, int z, Direction direction) {
        int nx = x + direction.offsetX(), nz = z + direction.offsetZ();
        boolean aboveOpen = !Blocks.getState(world.getBlock(x, y + 1, z)).isRedstoneConductor();
        BlockState neighbor = Blocks.getState(world.getBlock(nx, y, nz));
        boolean mayClimb = neighbor.getBlock().getBehavior(TrapdoorBehavior.class) != null
                || SupportBehavior.canSupportRedstoneWire(neighbor);
        if (aboveOpen && mayClimb
                && RedstonePower.isWire(Blocks.getState(world.getBlock(nx, y + 1, nz)))) {
            /* Vanilla zeichnet UP nur an einer voll tragenden Seitenflaeche. Bei Hoppern,
               oberen Slabs und passenden Trapdoors bleibt die Verbindung elektrisch
               bestehen, wird am unteren Staub aber flach als SIDE dargestellt. */
            return neighbor.getCollisionShape().isFaceFull(direction.opposite())
                    ? RedstoneSide.UP : RedstoneSide.SIDE;
        }
        if (RedstonePower.isWire(neighbor)) return RedstoneSide.SIDE;
        if (neighbor.getBlock().connectsRedstoneWire(neighbor, direction.opposite())) return RedstoneSide.SIDE;
        if (!neighbor.isRedstoneConductor()
                && RedstonePower.isWire(Blocks.getState(world.getBlock(nx, y - 1, nz)))) {
            return RedstoneSide.SIDE;
        }
        return RedstoneSide.NONE;
    }

    private RedstoneWireNetwork() {}
}
