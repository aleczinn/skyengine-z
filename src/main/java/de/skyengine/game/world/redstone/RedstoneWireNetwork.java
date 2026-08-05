package de.skyengine.game.world.redstone;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RedstoneSide;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    private static final Logger LOGGER = LogManager.getLogger(RedstoneWireNetwork.class.getName());

    /** Kappe der Komponenten-Größe: darüber bleibt der Rest stale bis zum nächsten Weckruf. */
    private static final int MAX_CELLS = 1024;

    /** Reentrancy-Guard (nur Tick-Thread): Empfänger-Benachrichtigungen dürfen kein zweites Netz starten. */
    private static boolean active;

    /** Berechnet die Komponente um (x,y,z) neu und benachrichtigt betroffene Empfänger. */
    public static void update(World world, int x, int y, int z) {
        if (active) {
            world.getSimulationTelemetry().recordSuppressedWireWake();
            return;
        }
        if (!RedstonePower.isWire(Blocks.getState(world.getBlock(x, y, z)))) return;
        world.getSimulationTelemetry().recordWireWake();
        active = true;
        try {
            run(world, x, y, z);
        } finally {
            active = false;
        }
    }

    private static void run(World world, int ox, int oy, int oz) {
        long telemetryStart = world.getSimulationTelemetry().beginRedstoneTiming();
        /* 1) Komponenten-BFS über die Staub-Zellen (feste Nachbar-Reihenfolge). */
        LinkedHashMap<Long, Integer> power = new LinkedHashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long origin = BlockPos.asLong(ox, oy, oz);
        power.put(origin, 0);
        queue.add(origin);
        boolean capped = false;
        while (!queue.isEmpty()) {
            long cell = queue.poll();
            int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
            for (long neighbor : wireNeighbors(world, cx, cy, cz)) {
                if (neighbor == Long.MIN_VALUE || power.containsKey(neighbor)) continue;
                if (power.size() >= MAX_CELLS) { capped = true; break; }
                power.put(neighbor, 0);
                queue.add(neighbor);
            }
        }
        if (capped) {
            LOGGER.warning("Staub-Netz bei (" + ox + ", " + oy + ", " + oz + ") überschreitet "
                    + MAX_CELLS + " Zellen — Rest bleibt bis zum nächsten Weckruf unverändert");
        }

        /* 2) Externe Quellen je Zelle + Relaxation als Bucket-BFS von 15 abwärts. */
        @SuppressWarnings("unchecked")
        ArrayDeque<Long>[] buckets = new ArrayDeque[16];
        for (int i = 0; i <= 15; i++) buckets[i] = new ArrayDeque<>();
        for (Map.Entry<Long, Integer> e : power.entrySet()) {
            long cell = e.getKey();
            int extern = RedstonePower.receivedPowerIgnoringWire(world,
                    BlockPos.unpackX(cell), BlockPos.unpackY(cell), BlockPos.unpackZ(cell));
            e.setValue(extern);
            if (extern > 0) buckets[extern].add(cell);
        }
        for (int level = 15; level > 1; level--) {
            ArrayDeque<Long> bucket = buckets[level];
            while (!bucket.isEmpty()) {
                long cell = bucket.poll();
                if (power.get(cell) != level) continue;   // veralteter Eintrag
                int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
                for (long neighbor : wireNeighbors(world, cx, cy, cz)) {
                    if (neighbor == Long.MIN_VALUE) continue;
                    Integer current = power.get(neighbor);
                    if (current == null || current >= level - 1) continue;   // nicht in der Komponente / schon heller
                    power.put(neighbor, level - 1);
                    buckets[level - 1].add(neighbor);
                }
            }
        }

        /* 3) Verbindungsform + neuen State je Zelle schreiben (Einfüge- = BFS-Reihenfolge). */
        List<Long> changed = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : power.entrySet()) {
            long cell = e.getKey();
            int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
            BlockState current = Blocks.getState(world.getBlock(cx, cy, cz));
            if (!RedstonePower.isWire(current)) continue;   // Rennen mit einem Abbau — Zelle überspringen

            RedstoneSide n = sideShape(world, cx, cy, cz, Direction.NORTH);
            RedstoneSide east = sideShape(world, cx, cy, cz, Direction.EAST);
            RedstoneSide s = sideShape(world, cx, cy, cz, Direction.SOUTH);
            RedstoneSide w = sideShape(world, cx, cy, cz, Direction.WEST);
            /* MC-Normalisierung (RedStoneWireBlock.getConnectionState, gegen 26.2 verifiziert):
               eine einzelne Verbindung wird zur durchgehenden Linie (Gegenseite als SIDE
               aufgefüllt), ein Staub ohne jede Verbindung wird zum KREUZ und speist damit alle
               vier Nachbarn.

               Die Punkt-Form überlebt nur, wenn der Staub schon vorher ein Punkt war — Vanillas
               `if (wasDot && isDot(neu)) return neu;`. Genau daran hing der Unterschied: die
               frühere Abkürzung „nur auffüllen, wenn es überhaupt eine Verbindung gibt" liess
               jeden isolierten Staub zum Punkt kollabieren, und ein Punkt speist horizontal
               nichts. Punkt und Kreuz sind reine Property-Kombinationen, keine eigene Property. */
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
                    .with(Properties.POWER, e.getValue());
            if (updated != current) {
                world.setBlock(cx, cy, cz, updated.getId(), false);
                changed.add(cell);
            }
        }

        /* 4) Empfänger EINMAL benachrichtigen. Reichweite wie MCs
           RedStoneWireBlock.updatePowerStrength: dort wird der 6-Ring nicht nur um die geänderte
           Zelle gefeuert, sondern auch um JEDEN ihrer 6 Nachbarn — die Vereinigung ist ein
           Diamant mit Radius 2. Der frühere Sonderfall „zusätzlich der Ring um stark gespeiste
           OPAKE Ziele" ist darin vollständig enthalten (solche Ziele sind direkte Nachbarn) und
           entfällt deshalb; die Opazitäts-Bedingung war zugleich der Grund, warum ein Kolben,
           der über Quasi-Konnektivität an einer LUFT-Zelle hängt, nie geweckt wurde.
           Staub-Zellen sind ausgenommen (direkte Netz-Nachbarn sind Teil der Komponente und
           schon konsistent). */
        LinkedHashSet<Long> notify = new LinkedHashSet<>();
        for (long cell : changed) {
            int cx = BlockPos.unpackX(cell), cy = BlockPos.unpackY(cell), cz = BlockPos.unpackZ(cell);
            addRing(notify, cx, cy, cz);
            for (Direction d : Direction.values()) {
                addRing(notify, cx + d.offsetX(), cy + d.offsetY(), cz + d.offsetZ());
            }
        }
        int receivers = 0;
        for (long pos : notify) {
            int nx = BlockPos.unpackX(pos), ny = BlockPos.unpackY(pos), nz = BlockPos.unpackZ(pos);
            if (RedstonePower.isWire(Blocks.getState(world.getBlock(nx, ny, nz)))) continue;
            world.updateBlockStateAt(nx, ny, nz);
            receivers++;
        }
        world.getSimulationTelemetry().recordWireSolve(telemetryStart, ox, oy, oz,
                power.size(), changed.size(), receivers, capped);
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
    private static void addRing(LinkedHashSet<Long> notify, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            notify.add(BlockPos.asLong(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ()));
        }
    }

    /**
     * Netz-Nachbarn einer Staub-Zelle in fester Reihenfolge: je Himmelsrichtung gleiche Ebene,
     * +1-Diagonale (nur wenn der Block ÜBER der eigenen Zelle nicht opak ist — Vanilla-
     * Kletterregel) und −1-Diagonale (nur wenn die Nachbarzelle selbst nicht opak ist).
     * {@code Long.MIN_VALUE} = kein Draht an der Stelle.
     */
    private static long[] wireNeighbors(World world, int x, int y, int z) {
        long[] out = new long[12];
        boolean aboveOpen = !Blocks.getState(world.getBlock(x, y + 1, z)).isOpaqueCube();
        int i = 0;
        for (Direction d : Direction.horizontalValues()) {
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            out[i++] = wireAt(world, nx, y, nz);
            out[i++] = aboveOpen ? wireAt(world, nx, y + 1, nz) : Long.MIN_VALUE;
            boolean sideOpen = !Blocks.getState(world.getBlock(nx, y, nz)).isOpaqueCube();
            out[i++] = sideOpen ? wireAt(world, nx, y - 1, nz) : Long.MIN_VALUE;
        }
        return out;
    }

    private static long wireAt(World world, int x, int y, int z) {
        return RedstonePower.isWire(Blocks.getState(world.getBlock(x, y, z)))
                ? BlockPos.asLong(x, y, z) : Long.MIN_VALUE;
    }

    /** Verbindungsform zu einer horizontalen Seite: UP (hochgezogen) &gt; SIDE &gt; NONE. */
    private static RedstoneSide sideShape(World world, int x, int y, int z, Direction d) {
        int nx = x + d.offsetX(), nz = z + d.offsetZ();
        boolean aboveOpen = !Blocks.getState(world.getBlock(x, y + 1, z)).isOpaqueCube();
        if (aboveOpen && RedstonePower.isWire(Blocks.getState(world.getBlock(nx, y + 1, nz)))) {
            return RedstoneSide.UP;
        }
        BlockState neighbor = Blocks.getState(world.getBlock(nx, y, nz));
        if (RedstonePower.isWire(neighbor)) return RedstoneSide.SIDE;
        if (neighbor.getBlock().connectsRedstoneWire(neighbor, d.opposite())) return RedstoneSide.SIDE;
        if (!neighbor.isOpaqueCube()
                && RedstonePower.isWire(Blocks.getState(world.getBlock(nx, y - 1, nz)))) {
            return RedstoneSide.SIDE;
        }
        return RedstoneSide.NONE;
    }

    private RedstoneWireNetwork() {}
}
