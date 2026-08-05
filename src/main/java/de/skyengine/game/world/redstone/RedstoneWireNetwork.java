package de.skyengine.game.world.redstone;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RedstoneSide;
import de.skyengine.utils.collect.LongIntMap;

import java.util.Arrays;

/**
 * Deterministische Staub-Signalausbreitung: statt MCs Update-Reihenfolge wird bei jedem
 * Weckruf die zusammenhängende Staub-Komponente als Ganzes auf ihren Fixpunkt gebracht —
 * {@code power(zelle) = max(externe Quellen, stärkster Netz-Nachbar − 1)}, gelöst als
 * Bucket-BFS von 15 abwärts. Das Ergebnis ist der eindeutige Fixpunkt und damit
 * ordnungsunabhängig; Staub ist wie in Vanilla instant, Verstärker/Fackel takten getrennt
 * über geplante Ticks.
 *
 * <p>Läuft ausschließlich auf dem Tick-Thread, alle Writes mit {@code updateNeighbors=false}
 * (die Empfänger-Benachrichtigung ist gezielt und dedupliziert) — die Nicht-Kaskadierungs-
 * Regel von {@code World.updateStateAt} bleibt unangetastet.
 *
 * <p>Zellen in nicht-READY-Chunks liest {@code getBlock} als Luft — die Komponente endet
 * dort und der Grenz-Staub bleibt stale, bis ihn der nächste Weckruf erreicht. Akzeptierte
 * Einschränkung: Redstone lebt praktisch in der Simulations-Distanz.
 */
public final class RedstoneWireNetwork {

    /** Verhindert, dass eine einzelne dichte Matrix schon vor dem Deduplizieren riesig reserviert. */
    private static final int MAX_INITIAL_NOTIFICATION_CAPACITY = 16_384;

    /**
     * Reentrancy-Guard pro Thread und Welt: Empfänger-Benachrichtigungen dürfen kein zweites
     * Netz derselben Welt starten. Unabhängige Welten auf verschiedenen Threads dürfen sich
     * dagegen nicht gegenseitig unterdrücken (parallele Tests, spätere Server-Nutzung).
     */
    private static final ThreadLocal<World> ACTIVE_WORLD = new ThreadLocal<>();

    /** Berechnet die Komponente um (x,y,z) neu und benachrichtigt betroffene Empfänger. */
    public static void update(World world, int x, int y, int z) {
        update(world, x, y, z, null);
    }

    /**
     * Wie {@link #update}, überspringt aber Komponenten, die während desselben Chunk-Kanten-
     * Abgleichs bereits gelöst wurden. Alle gefundenen Staubzellen werden in {@code visited}
     * eingetragen; dadurch kostet ein langes Netz entlang einer Kante nur einen Solve.
     */
    public static void updateOncePerComponent(World world, int x, int y, int z, LongIntMap visited) {
        update(world, x, y, z, visited);
    }

    private static void update(World world, int x, int y, int z, LongIntMap visited) {
        World previousActiveWorld = ACTIVE_WORLD.get();
        if (previousActiveWorld == world) {
            world.getSimulationTelemetry().recordSuppressedWireWake();
            return;
        }
        long origin = BlockPos.asLong(x, y, z);
        if (visited != null && visited.containsKey(origin)) return;
        if (!RedstonePower.isWire(Blocks.getState(world.getBlock(x, y, z)))) return;
        world.getSimulationTelemetry().recordWireWake();
        ACTIVE_WORLD.set(world);
        try {
            run(world, x, y, z, visited);
        } finally {
            if (previousActiveWorld == null) {
                ACTIVE_WORLD.remove();
            } else {
                ACTIVE_WORLD.set(previousActiveWorld);
            }
        }
    }

    private static void run(World world, int ox, int oy, int oz, LongIntMap visited) {
        long telemetryStart = world.getSimulationTelemetry().beginRedstoneTiming();
        /* 1) Komponenten-BFS über die Staub-Zellen (feste Nachbar-Reihenfolge). */
        LongIntMap power = new LongIntMap(1024);
        LongBuffer cells = new LongBuffer(1024);
        long[] neighbors = new long[12];
        long origin = BlockPos.asLong(ox, oy, oz);
        power.put(origin, 0);
        if (visited != null) visited.put(origin, 1);
        cells.add(origin);
        for (int cursor = 0; cursor < cells.size(); cursor++) {
            long cell = cells.get(cursor);
            int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
            wireNeighbors(world, cx, cy, cz, neighbors);
            for (long neighbor : neighbors) {
                if (neighbor == Long.MIN_VALUE || power.containsKey(neighbor)) continue;
                power.put(neighbor, 0);
                if (visited != null) visited.put(neighbor, 1);
                cells.add(neighbor);
            }
        }

        /* 2) Externe Quellen je Zelle + Relaxation als Bucket-BFS von 15 abwärts. */
        LongBuffer[] buckets = new LongBuffer[16];
        for (int i = 0; i <= 15; i++) buckets[i] = new LongBuffer(16);
        for (int i = 0; i < cells.size(); i++) {
            long cell = cells.get(i);
            int extern = RedstonePower.receivedPowerIgnoringWire(world,
                    BlockPos.unpackX(cell), BlockPos.unpackY(cell), BlockPos.unpackZ(cell));
            power.put(cell, extern);
            if (extern > 0) buckets[extern].add(cell);
        }
        for (int level = 15; level > 1; level--) {
            LongBuffer bucket = buckets[level];
            for (int cursor = 0; cursor < bucket.size(); cursor++) {
                long cell = bucket.get(cursor);
                if (power.getOrDefault(cell, -1) != level) continue;   // veralteter Eintrag
                int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
                wireNeighbors(world, cx, cy, cz, neighbors);
                for (long neighbor : neighbors) {
                    if (neighbor == Long.MIN_VALUE) continue;
                    int current = power.getOrDefault(neighbor, -1);
                    if (current < 0 || current >= level - 1) continue;   // nicht im Netz / schon heller
                    power.put(neighbor, level - 1);
                    buckets[level - 1].add(neighbor);
                }
            }
        }

        /* 3) Verbindungsform + neuen State je Zelle schreiben (Einfüge- = BFS-Reihenfolge). */
        LongBuffer changed = new LongBuffer(Math.min(1024, cells.size()));
        for (int i = 0; i < cells.size(); i++) {
            long cell = cells.get(i);
            int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
            BlockState current = Blocks.getState(world.getBlock(cx, cy, cz));
            if (!RedstonePower.isWire(current)) continue;

            RedstoneSide n = sideShape(world, cx, cy, cz, Direction.NORTH);
            RedstoneSide east = sideShape(world, cx, cy, cz, Direction.EAST);
            RedstoneSide s = sideShape(world, cx, cy, cz, Direction.SOUTH);
            RedstoneSide w = sideShape(world, cx, cy, cz, Direction.WEST);
            /* MC-Normalisierung: eine einzelne Verbindung wird zur durchgehenden Linie,
               ein verbindungsloser Staub zum Kreuz. Nur ein bewusst gesetzter Punkt bleibt
               ohne Verbindungen ein Punkt. */
            boolean cn = n.isConnected(), ce = east.isConnected(), cs = s.isConnected(), cw = w.isConnected();
            if (!(isDot(current) && !cn && !ce && !cs && !cw)) {
                boolean noNS = !cn && !cs, noEW = !ce && !cw;
                if (!cw && noNS) w = RedstoneSide.SIDE;
                if (!ce && noNS) east = RedstoneSide.SIDE;
                if (!cn && noEW) n = RedstoneSide.SIDE;
                if (!cs && noEW) s = RedstoneSide.SIDE;
            }

            BlockState updated = current
                    .with(Properties.WIRE_NORTH, n)
                    .with(Properties.WIRE_EAST, east)
                    .with(Properties.WIRE_SOUTH, s)
                    .with(Properties.WIRE_WEST, w)
                    .with(Properties.POWER, power.getOrDefault(cell, 0));
            if (updated != current) {
                world.setBlock(cx, cy, cz, updated.getId(), false);
                changed.add(cell);
            }
        }

        /* 4) Empfänger EINMAL in stabiler Einfügereihenfolge benachrichtigen. Der Ring um
           jede geänderte Zelle und deren sechs Nachbarn ergibt den MC-Radius-2-Diamanten;
           Staub selbst ist schon durch den vollständigen Netz-Fixpunkt konsistent. */
        int notificationCapacity = notificationInitialCapacity(changed.size());
        LongIntMap notifySet = new LongIntMap(notificationCapacity);
        LongBuffer notify = new LongBuffer(notificationCapacity);
        for (int i = 0; i < changed.size(); i++) {
            long cell = changed.get(i);
            int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
            addRing(notifySet, notify, cx, cy, cz);
            for (Direction d : Direction.values()) {
                addRing(notifySet, notify,
                        cx + d.offsetX(), cy + d.offsetY(), cz + d.offsetZ());
            }
        }
        int receivers = 0;
        for (int i = 0; i < notify.size(); i++) {
            long pos = notify.get(i);
            int nx = BlockPos.unpackX(pos), ny = BlockPos.unpackY(pos), nz = BlockPos.unpackZ(pos);
            if (RedstonePower.isWire(Blocks.getState(world.getBlock(nx, ny, nz)))) continue;
            world.updateBlockStateAt(nx, ny, nz);
            receivers++;
        }
        world.getSimulationTelemetry().recordWireSolve(telemetryStart, ox, oy, oz,
                power.size(), changed.size(), receivers, false);
    }

    /**
     * Startkapazität, kein Inhaltsdeckel. Die frühere Schätzung {@code changed * 8}
     * überreservierte bei dichten Matrizen massiv, weil sich deren Radius-2-Mengen fast
     * vollständig überlappen. Set und Puffer wachsen weiterhin verlustfrei, wenn eine dünne
     * Struktur tatsächlich mehr eindeutige Empfänger erzeugt.
     */
    static int notificationInitialCapacity(int changedCells) {
        return Math.max(16, Math.min(changedCells, MAX_INITIAL_NOTIFICATION_CAPACITY));
    }

    /** Punkt-Form: keine einzige Seite verbunden (speist horizontal nichts). */
    public static boolean isDot(BlockState state) {
        for (Direction d : Direction.horizontalValues()) {
            if (state.get(Properties.wireSide(d)).isConnected()) return false;
        }
        return true;
    }

    /** Kreuz-Form: alle vier Seiten auf SIDE (speist alle vier Nachbarn). */
    public static boolean isCross(BlockState state) {
        for (Direction d : Direction.horizontalValues()) {
            if (state.get(Properties.wireSide(d)) != RedstoneSide.SIDE) return false;
        }
        return true;
    }

    /** Der Kreuz-State zu einem gegebenen Staub-State (Vanillas {@code crossState}). */
    public static BlockState toCross(BlockState state) {
        for (Direction d : Direction.horizontalValues()) {
            state = state.with(Properties.wireSide(d), RedstoneSide.SIDE);
        }
        return state;
    }

    /** Der Punkt-State zu einem gegebenen Staub-State. */
    public static BlockState toDot(BlockState state) {
        for (Direction d : Direction.horizontalValues()) {
            state = state.with(Properties.wireSide(d), RedstoneSide.NONE);
        }
        return state;
    }

    /** Die 6 direkten Nachbarn von (x,y,z) in die Empfänger-Menge legen. */
    private static void addRing(LongIntMap notifySet, LongBuffer notify, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            long pos = BlockPos.asLong(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
            if (notifySet.containsKey(pos)) continue;
            notifySet.put(pos, 1);
            notify.add(pos);
        }
    }

    /**
     * Netz-Nachbarn einer Staub-Zelle in fester Reihenfolge: je Himmelsrichtung gleiche Ebene,
     * +1-Diagonale (nur wenn der Block ÜBER der eigenen Zelle kein Redstone-Leiter ist —
     * Vanilla-Kletterregel) und −1-Diagonale (nur wenn die Nachbarzelle kein Leiter ist).
     * {@code Long.MIN_VALUE} = kein Draht an der Stelle.
     */
    private static void wireNeighbors(World world, int x, int y, int z, long[] out) {
        boolean aboveOpen = !Blocks.getState(world.getBlock(x, y + 1, z)).isRedstoneConductor();
        int i = 0;
        for (Direction d : Direction.horizontalValues()) {
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            out[i++] = wireAt(world, nx, y, nz);
            out[i++] = aboveOpen ? wireAt(world, nx, y + 1, nz) : Long.MIN_VALUE;
            boolean sideOpen = !Blocks.getState(world.getBlock(nx, y, nz)).isRedstoneConductor();
            out[i++] = sideOpen ? wireAt(world, nx, y - 1, nz) : Long.MIN_VALUE;
        }
    }

    /** Primitive, wachsender Puffer; bewahrt die deterministische BFS-/Benachrichtigungsfolge. */
    private static final class LongBuffer {
        private long[] values;
        private int size;

        LongBuffer(int initialCapacity) {
            this.values = new long[Math.max(1, initialCapacity)];
        }

        void add(long value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length << 1);
            }
            this.values[this.size++] = value;
        }

        long get(int index) {
            return this.values[index];
        }

        int size() {
            return this.size;
        }
    }

    private static long wireAt(World world, int x, int y, int z) {
        return RedstonePower.isWire(Blocks.getState(world.getBlock(x, y, z)))
                ? BlockPos.asLong(x, y, z) : Long.MIN_VALUE;
    }

    /** Verbindungsform zu einer horizontalen Seite: UP (hochgezogen) &gt; SIDE &gt; NONE. */
    private static RedstoneSide sideShape(World world, int x, int y, int z, Direction d) {
        int nx = x + d.offsetX(), nz = z + d.offsetZ();
        boolean aboveOpen = !Blocks.getState(world.getBlock(x, y + 1, z)).isRedstoneConductor();
        if (aboveOpen && RedstonePower.isWire(Blocks.getState(world.getBlock(nx, y + 1, nz)))) {
            return RedstoneSide.UP;
        }
        BlockState neighbor = Blocks.getState(world.getBlock(nx, y, nz));
        if (RedstonePower.isWire(neighbor)) return RedstoneSide.SIDE;
        if (neighbor.getBlock().connectsRedstoneWire(neighbor, d.opposite())) return RedstoneSide.SIDE;
        if (!neighbor.isRedstoneConductor()
                && RedstonePower.isWire(Blocks.getState(world.getBlock(nx, y - 1, nz)))) {
            return RedstoneSide.SIDE;
        }
        return RedstoneSide.NONE;
    }

    private RedstoneWireNetwork() {}
}
