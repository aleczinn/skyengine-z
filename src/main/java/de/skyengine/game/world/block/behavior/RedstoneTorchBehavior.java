package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;

import java.util.HashMap;
import java.util.Map;

/**
 * Redstone-Fackel, der Inverter: aus, sobald ihr Trägerblock ein Signal führt — mit einem
 * Redstone-Tick (2 Game-Ticks) Verzögerung über den Scheduler. Leuchtend gibt sie 15 in
 * alle Richtungen außer zum Träger ab, stark nur nach OBEN (deshalb schaltet eine Fackel
 * unter einem Block alles über diesem Block).
 *
 * <p><b>Burnout (MC):</b> Schaltet die Fackel zu oft in zu kurzer Zeit — klassisch die
 * Fackel unter einem Block mit Staub daneben, die sich selbst abschaltet —, brennt sie durch
 * und bleibt {@value #BURNOUT_RECOVERY} Ticks aus. Gezählt werden nur die AUS-Flanken; die
 * AN-Flanke fragt das Protokoll nur ab. Ohne das entsteht eine Endlos-Clock.
 *
 * <p>„Ausgebrannt" braucht keine eigene Property: es ist {@code lit=false} plus die transiente
 * Historie plus der anstehende Erholungs-Tick (der persistiert). Nach einem Reload ist die
 * Historie leer — das ist der harmlose Fall (die Fackel läuft und brennt eben erneut durch),
 * anders als beim Beobachter braucht es hier also kein Seeding.
 */
public final class RedstoneTorchBehavior implements BlockBehavior {

    /** MC-Zahlen: mehr als {@value} Aus-Flanken im Fenster {@value #BURNOUT_WINDOW} = durchgebrannt. */
    private static final int BURNOUT_LIMIT = 8;
    private static final int BURNOUT_WINDOW = 60;
    private static final int BURNOUT_RECOVERY = 160;

    /**
     * Position -> Aus-Flanken im laufenden Fenster. Nur Tick-Thread, deshalb ungesichert
     * (Muster {@code PressurePlateBehavior.touches}). Transient und bewusst nicht persistiert.
     */
    private final Map<Long, Toggles> recent = new HashMap<>();

    /** Zähler mit Fensterbeginn. Mutable wie {@code PressurePlateBehavior.Touch}. */
    private static final class Toggles {
        long windowStart;
        int count;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (shouldBeLit(world, x, y, z, state) != state.get(Properties.LIT)
                && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, 2);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        /* Tolerantes Feuern: neu prüfen — hat sich das Signal zurückgedreht, passiert nichts. */
        boolean lit = shouldBeLit(world, x, y, z, state);
        if (lit == state.get(Properties.LIT)) return;

        if (lit) {
            /* AN-Flanke: nur das Protokoll LESEN. Ist die Fackel durchgebrannt, bleibt sie aus —
               der Erholungs-Tick holt das Einschalten nach. */
            if (this.isBurntOut(world, x, y, z)) return;
        } else if (this.recordToggle(world, x, y, z)) {
            /* AUS-Flanke hat das Limit gerissen: ausschalten wie sonst auch, aber die Erholung
               einplanen und die Historie schliessen (sie wird beim Erholungs-Tick verworfen). */
            world.scheduleTick(x, y, z, BURNOUT_RECOVERY);
        }

        world.setBlock(x, y, z, state.with(Properties.LIT, lit).getId(), false);
        world.updateNeighborsWide(x, y, z);
    }

    /**
     * Trägt eine Aus-Flanke ein; {@code true}, wenn die Fackel damit durchbrennt.
     *
     * <p>Zeitbasis ist {@code world.getGameTime()}. Die wird NICHT persistiert und startet in
     * jeder Welt wieder bei 0, die Behavior-Instanz lebt aber über Weltwechsel hinweg — ein
     * Zeitstempel aus der „Zukunft" muss deshalb genauso als veraltet gelten wie ein zu alter,
     * sonst schlösse sich das Fenster nach einem Weltwechsel nie wieder.
     */
    private boolean recordToggle(World world, int x, int y, int z) {
        long now = world.getGameTime();
        Toggles toggles = this.recent.computeIfAbsent(key(x, y, z), k -> new Toggles());
        long verstrichen = now - toggles.windowStart;
        if (verstrichen < 0 || verstrichen > BURNOUT_WINDOW) {
            toggles.windowStart = now;
            toggles.count = 0;
        }
        return ++toggles.count >= BURNOUT_LIMIT;
    }

    /** Ausgebrannt, solange das Limit im noch offenen Fenster gerissen ist. */
    private boolean isBurntOut(World world, int x, int y, int z) {
        Toggles toggles = this.recent.get(key(x, y, z));
        if (toggles == null) return false;
        long verstrichen = world.getGameTime() - toggles.windowStart;
        if (verstrichen < 0 || verstrichen > BURNOUT_WINDOW) {
            this.recent.remove(key(x, y, z));   // Fenster vorbei: Erholung
            return false;
        }
        return toggles.count >= BURNOUT_LIMIT;
    }

    private static long key(int x, int y, int z) {
        return de.skyengine.game.world.block.BlockPos.asLong(x, y, z);
    }

    /* Die Fackel ist neben dem Staub die zweite Quelle, der MC den vollen Radius-2-Diamanten
       gibt (RedstoneTorchBlock.notifyNeighbors — bei Platzieren, Abbauen und jeder Flanke).
       Er deckt das starke Ziel oben ab UND einen Kolben, der über Quasi-Konnektivität an einer
       Nachbarzelle der Fackel hängt (der ist selbst kein Nachbar der Fackel). */

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        world.updateNeighborsWide(x, y, z);
    }

    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        this.recent.remove(key(x, y, z));   // sonst wächst die Map über die Sitzung
        /* onBreak läuft VOR dem Entfernen — aufschieben, sonst läse der Ring die Fackel noch. */
        world.deferBlockUpdatesWide(x, y, z);
    }

    /** An, solange der Trägerblock KEIN Signal in die Fackel speist (Inverter). */
    private static boolean shouldBeLit(World world, int x, int y, int z, BlockState state) {
        Direction support = ButtonBehavior.supportDirection(state);
        int sx = x + support.offsetX(), sy = y + support.offsetY(), sz = z + support.offsetZ();
        return RedstonePower.emittedSignal(world, sx, sy, sz, support.opposite(), false) == 0;
    }

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        if (!state.get(Properties.LIT)) return 0;
        return side == ButtonBehavior.supportDirection(state) ? 0 : 15;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.LIT) && side == Direction.UP ? 15 : 0;
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return true;
    }
}
