package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Redstone-Fackel, der Inverter: aus, sobald ihr Trägerblock ein Signal führt — mit einem
 * Redstone-Tick (2 Game-Ticks) Verzögerung über den Scheduler. Leuchtend gibt sie 15 in
 * alle Richtungen außer zum Träger ab, stark nur nach OBEN (deshalb schaltet eine Fackel
 * unter einem Block alles über diesem Block).
 *
 * <p><b>Burnout (MC):</b> Schaltet die Fackel {@value #BURNOUT_LIMIT} Mal aus innerhalb von
 * {@value #BURNOUT_WINDOW} Ticks — klassisch die Fackel, deren Staub den eigenen Träger speist —,
 * brennt sie durch: sie zischt und geht aus. Gezählt werden nur die AUS-Flanken; die AN-Flanke
 * fragt das Protokoll nur ab.
 *
 * <p>Beim Durchbrennen plant Vanilla einen Neustart-Tick nach {@value #RESTART_DELAY} Game-Ticks.
 * Bis dahin bleibt die Fackel aus; beim Tick sind alle Einträge des 60-Tick-Fensters veraltet und
 * sie schaltet selbstständig wieder ein, sofern ihr Träger nicht gespeist wird.
 *
 * <p>„Ausgebrannt" braucht keine eigene Property: es ist {@code lit=false} plus die transiente
 * Historie. Nach einem Reload ist die Historie leer — das ist der harmlose Fall (die Fackel folgt
 * wieder und brennt eben erneut durch). Observer benötigen ebenfalls keinen transienten Verlauf:
 * Vanilla reagiert dort direkt auf gerichtete Shape-Updates.
 */
public final class RedstoneTorchBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    /** MC-Zahlen ({@code MAX_RECENT_TOGGLES} / {@code RECENT_TOGGLE_TIMER}). */
    private static final int BURNOUT_LIMIT = 8;
    private static final int BURNOUT_WINDOW = 60;
    private static final int RESTART_DELAY = 160;

    /** Welt- und Chunk-gebundene Aus-Flanken; transient und bewusst nicht persistiert. */
    private final WorldScopedPositionMap<Toggles> recentByWorld = new WorldScopedPositionMap<>();
    @SuppressWarnings("FieldMayBeFinal") // Referenz wechselt mit der aktiven Welt (Headless-Diagnose).
    private Map<Long, ?> recent = new HashMap<>();

    /** Einzelne Aus-Flanken im gleitenden Vanilla-Zeitfenster. */
    private static final class Toggles {
        final ArrayDeque<Long> when = new ArrayDeque<>();
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (shouldBeLit(world, x, y, z, state) != state.get(Properties.LIT)
                && !world.willTickThisTick(x, y, z)) {
            /* Vanilla plant regulär; ein bereits wartender Burnout-Neustart wird nicht vorgezogen. */
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
            /* AN-Flanke: das Protokoll nur lesen; der 160-Tick-Neustart kommt später erneut. */
            if (this.isBurntOut(world, x, y, z)) return;
        } else if (this.recordToggle(world, x, y, z)) {
            for (int i = 0; i < 5; i++) {
                world.particles().smoke(x + 0.5, y + 0.7, z + 0.5, false,
                        de.skyengine.game.world.particle.ParticlePriority.NORMAL);
            }
            /* AUS-Flanke hat das Limit gerissen: zischen und selbstständigen Neustart planen. */
            if (world.getSoundManager() != null) {
                world.getSoundManager().playFizz(x + 0.5, y + 0.5, z + 0.5);
            }
            world.scheduleTick(x, y, z, RESTART_DELAY);
        }

        /* Vanilla schreibt die Flanke mit Flag 3: Der direkte General-/Shape-Ring gehoert
           zusaetzlich zu notifyNeighbors' verschachtelten sechs Nachbarringen. Ohne diesen
           ersten Ring sieht unmittelbar angrenzender Staub die AUS-Flanke nicht und behaelt
           seinen gespeicherten POWER-State, bis ihn eine Spieleraktion erneut berechnet. */
        world.setBlock(x, y, z, state.with(Properties.LIT, lit).getId(), true);
        world.updateGeneralNeighborsAroundAdjacentCells(x, y, z);
    }

    /**
     * Trägt eine Aus-Flanke ein; {@code true}, wenn die Fackel damit durchbrennt.
     *
     * <p>Zeitbasis ist {@code world.getGameTime()}. Die wird NICHT persistiert; der transiente
     * Speicher ist deshalb pro Welt getrennt. Der defensive Zukunfts-Check bleibt für Tests und
     * Werkzeuge erhalten, die die Spielzeit derselben Welt gezielt zurücksetzen.
     */
    private boolean recordToggle(World world, int x, int y, int z) {
        long now = world.getGameTime();
        this.recent = this.recentByWorld.diagnosticEntries(world);
        Toggles toggles = this.recentByWorld.computeIfAbsent(world, x, y, z, Toggles::new);
        prune(toggles, now);
        toggles.when.addLast(now);
        return toggles.when.size() >= BURNOUT_LIMIT;
    }

    /**
     * Ausgebrannt, solange im noch offenen Zählfenster die Schwelle gerissen ist.
     *
     * <p>Vanillas Gegenstück ist der Prune-Lauf am Anfang von {@code tick}: er wirft Einträge
     * weg, die älter als {@value #BURNOUT_WINDOW} Ticks sind — erst danach zählt die Schwelle
     * nicht mehr und die Fackel darf wieder. Ein Zeitstempel aus der „Zukunft" (Weltwechsel,
     * s. {@link #recordToggle}) gilt genauso als abgelaufen.
     */
    private boolean isBurntOut(World world, int x, int y, int z) {
        this.recent = this.recentByWorld.diagnosticEntries(world);
        Toggles toggles = this.recentByWorld.get(world, x, y, z);
        if (toggles == null) return false;
        prune(toggles, world.getGameTime());
        if (toggles.when.isEmpty()) {
            this.recentByWorld.remove(world, x, y, z);   // Fenster durch: der Zähler ist wertlos
            return false;
        }
        return toggles.when.size() >= BURNOUT_LIMIT;
    }

    private static void prune(Toggles toggles, long now) {
        while (!toggles.when.isEmpty()) {
            long age = now - toggles.when.peekFirst();
            if (age >= 0 && age <= BURNOUT_WINDOW) return;
            toggles.when.removeFirst();
        }
    }

    /* Vanilla startet um jede der sechs Nachbarzellen einen eigenen allgemeinen Update-Ring.
       Das erreicht Radius 2, bewahrt aber die beobachtbaren Duplikate und Reihenfolgen. */

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        world.updateGeneralNeighborsAroundAdjacentCells(x, y, z);
    }

    @Override
    public void onRemoved(World world, int x, int y, int z,
                          BlockState oldState, BlockState newState) {
        this.recent = this.recentByWorld.diagnosticEntries(world);
        this.recentByWorld.remove(world, x, y, z);
        world.updateGeneralNeighborsAroundAdjacentCells(x, y, z);
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

    @Override
    public boolean isRedstoneSignalSource() {
        return true;
    }
}
