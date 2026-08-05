package de.skyengine.game.world.redstone;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.Blocks;

/**
 * Zentrale Redstone-Signal-Abfragen (Vanilla-Semantik, deterministisch):
 *
 * <ul>
 *   <li><b>Schwach</b> ({@code weakPower}): wirkt nur auf direkte Nachbarn.</li>
 *   <li><b>Stark</b> ({@code strongPower}): macht einen LEITENDEN Nachbarblock selbst zur
 *       Quelle — der leitet das Signal als schwaches weiter (Hebel am Block schaltet
 *       die Tür dahinter, Staub aktiviert "durch" den Block, in den er einspeist).</li>
 *   <li>{@code ignoreWire}: überspringt Redstone-Staub als Quelle. Der Staub selbst fragt
 *       damit ab — Staub-zu-Staub läuft AUSSCHLIESSLICH über das {@code RedstoneWireNetwork}
 *       (Vanilla: das globale shouldSignal-Flag des RedStoneWireBlock), sonst zählte eine
 *       Staubzelle ihren eigenen Netz-Nachbarn doppelt als externe Quelle.</li>
 * </ul>
 *
 * Die Rekursionstiefe ist hart begrenzt: emittedSignal → strongPowerInto → nur noch
 * strongPower-Hooks, die nie zurückfragen. Kein Rekursionsschutz nötig. Nur Tick-Thread.
 */
public final class RedstonePower {

    /**
     * Signal, das der Block an (x,y,z) in Richtung {@code toward} abgibt: sein eigenes
     * schwaches Signal, bei Redstone-Leitern zusätzlich das stark empfangene.
     */
    public static int emittedSignal(World world, int x, int y, int z, Direction toward, boolean ignoreWire) {
        BlockState state = Blocks.getState(world.getBlock(x, y, z));
        if (state.isAir()) return 0;
        if (ignoreWire && isWire(state)) return 0;

        int power = state.getBlock().getWeakPower(world, x, y, z, state, toward);
        if (power >= 15) return 15;
        if (state.isRedstoneConductor()) {
            power = Math.max(power, strongPowerInto(world, x, y, z, ignoreWire));
        }
        return Math.min(15, power);
    }

    /** Stark empfangenes Signal der Zelle: max über die strongPower der 6 Nachbarn in ihre Richtung. */
    public static int strongPowerInto(World world, int x, int y, int z, boolean ignoreWire) {
        int power = 0;
        for (Direction d : Direction.values()) {
            int nx = x + d.offsetX(), ny = y + d.offsetY(), nz = z + d.offsetZ();
            BlockState neighbor = Blocks.getState(world.getBlock(nx, ny, nz));
            if (neighbor.isAir()) continue;
            if (ignoreWire && isWire(neighbor)) continue;
            power = Math.max(power, neighbor.getBlock().getStrongPower(world, nx, ny, nz, neighbor, d.opposite()));
            if (power >= 15) return 15;
        }
        return power;
    }

    /** Empfänger-Sicht (Tür, Lampe, Verstärker-Eingang): max über die Signale der 6 Nachbarn. */
    public static int receivedPower(World world, int x, int y, int z) {
        return received(world, x, y, z, false);
    }

    /** true, wenn irgendein Nachbar die Zelle mit Signal &gt; 0 versorgt. */
    public static boolean isReceiving(World world, int x, int y, int z) {
        return received(world, x, y, z, false) > 0;
    }

    /** Wie {@link #receivedPower}, aber ohne Staub als Quelle — die Abfrage des Staubs selbst. */
    public static int receivedPowerIgnoringWire(World world, int x, int y, int z) {
        return received(world, x, y, z, true);
    }

    private static int received(World world, int x, int y, int z, boolean ignoreWire) {
        int power = 0;
        for (Direction d : Direction.values()) {
            power = Math.max(power, emittedSignal(world,
                    x + d.offsetX(), y + d.offsetY(), z + d.offsetZ(), d.opposite(), ignoreWire));
            if (power >= 15) return 15;
        }
        return power;
    }

    /**
     * Redstone-Staub-Erkennung über die Property-Identität (POWER + Staub-Verbindung) —
     * ohne Registry-Kopplung, dasselbe Muster wie {@code DoorBehavior.isDoor} über HINGE.
     */
    public static boolean isWire(BlockState state) {
        return state.getValues().containsKey(Properties.POWER)
                && state.getValues().containsKey(Properties.WIRE_NORTH);
    }

    /**
     * Gilt der Block als Quelle für die SEITEN-Eingänge eines Komparators bzw. fürs
     * Repeater-Locking? Wie MC nur „echte" Redstone-Komponenten: Staub, Verstärker
     * (DELAY), Komparator (MODE) und der Redstone-Block (konstante Quelle).
     */
    public static boolean isSideInputSource(BlockState state) {
        if (isWire(state)) return true;
        if (state.getValues().containsKey(Properties.DELAY)) return true;
        if (state.getValues().containsKey(Properties.MODE)) return true;
        /* Redstone-Block: konstante Quelle ohne Properties, verbindet sich mit Staub. */
        return state.getValues().isEmpty()
                && state.getBlock().connectsRedstoneWire(state, de.skyengine.game.world.block.Direction.NORTH);
    }

    private RedstonePower() {}
}
