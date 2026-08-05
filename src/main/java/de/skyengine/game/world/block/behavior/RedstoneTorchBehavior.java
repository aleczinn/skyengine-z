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
 * <p><b>Burnout (MC):</b> Schaltet die Fackel {@value #BURNOUT_LIMIT} Mal aus innerhalb von
 * {@value #BURNOUT_WINDOW} Ticks — klassisch die Fackel, deren Staub den eigenen Träger speist —,
 * brennt sie durch: sie zischt und geht aus. Gezählt werden nur die AUS-Flanken; die AN-Flanke
 * fragt das Protokoll nur ab.
 *
 * <p><b>Und dann bleibt sie aus — von selbst kommt sie NIE zurück.</b> Das ist am Bytecode von
 * 26.2 verifiziert und der Punkt, an dem eine naive Umsetzung danebenliegt: Vanilla plant beim
 * Durchbrennen zwar {@code scheduleTick(160)}, aber dieser Eintrag wird von der Tick-Queue
 * verworfen — die Redstone-Kaskade des Ausschaltens läuft synchron im selben Aufruf und hat für
 * dieselbe Position längst einen 2-Tick-Eintrag gesetzt. Der feuert, findet die Historie noch
 * frisch, steigt früh aus und plant NICHTS nach. Danach gibt es keinen Tick mehr.
 *
 * <p>Zurück kommt die Fackel deshalb nur über ein <b>Nachbar-Update</b>, und auch dann erst, wenn
 * seit der letzten Aus-Flanke mehr als {@value #BURNOUT_WINDOW} Ticks vergangen sind (vorher ist
 * das Zählfenster noch offen). Praktisch heißt das: Block über der Fackel entfernen → sie leuchtet.
 *
 * <p>„Ausgebrannt" braucht keine eigene Property: es ist {@code lit=false} plus die transiente
 * Historie. Nach einem Reload ist die Historie leer — das ist der harmlose Fall (die Fackel folgt
 * wieder und brennt eben erneut durch), anders als beim Beobachter braucht es hier kein Seeding.
 */
public final class RedstoneTorchBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    /** MC-Zahlen ({@code MAX_RECENT_TOGGLES} / {@code RECENT_TOGGLE_TIMER}). */
    private static final int BURNOUT_LIMIT = 8;
    private static final int BURNOUT_WINDOW = 60;

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
        if (shouldBeLit(world, x, y, z, state) != state.get(Properties.LIT)) {
            /* Vorziehen statt „nur wenn gar nichts ansteht": eine ausgebrannte Fackel hat den
               Erholungs-Tick in der Queue und wäre sonst bis zu 160 Ticks taub — sie muss aber
               sofort reagieren, wenn sich ihre Lage ändert (Block darüber weg). MC prüft an
               dieser Stelle nur, ob ein Tick für GENAU DIESEN Tick ansteht. */
            world.scheduleTickEarlier(x, y, z, 2);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        /* Tolerantes Feuern: neu prüfen — hat sich das Signal zurückgedreht, passiert nichts. */
        boolean lit = shouldBeLit(world, x, y, z, state);
        if (lit == state.get(Properties.LIT)) return;

        if (lit) {
            /* AN-Flanke: das Protokoll nur LESEN. Ist die Fackel ausgebrannt, endet der Tick hier
               — und mit ihm die Tick-Kette. GENAU DAS ist das dauerhafte Aus: würde hier ein
               Folge-Tick geplant, käme die Fackel ohne äusseren Anlass zurück und aus dem
               Durchbrennen würde ein Takt. Zurück holt sie nur ein Nachbar-Update, und auch das
               erst, wenn das Zählfenster abgelaufen ist. */
            if (this.isBurntOut(world, x, y, z)) return;
        } else if (this.recordToggle(world, x, y, z)) {
            /* AUS-Flanke hat das Limit gerissen: nur zischen. Vanillas scheduleTick(160) an
               dieser Stelle ist wirkungslos — die Queue hat für die Position längst den 2-Tick-
               Eintrag aus der Schalt-Kaskade und verwirft ihn. Bei uns würde er dagegen feuern
               und die Fackel fälschlich zurückholen, deshalb steht er hier gar nicht erst. */
            if (world.getSoundManager() != null) {
                world.getSoundManager().playFizz(x + 0.5, y + 0.5, z + 0.5);
            }
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

    /**
     * Ausgebrannt, solange im noch offenen Zählfenster die Schwelle gerissen ist.
     *
     * <p>Vanillas Gegenstück ist der Prune-Lauf am Anfang von {@code tick}: er wirft Einträge
     * weg, die älter als {@value #BURNOUT_WINDOW} Ticks sind — erst danach zählt die Schwelle
     * nicht mehr und die Fackel darf wieder. Ein Zeitstempel aus der „Zukunft" (Weltwechsel,
     * s. {@link #recordToggle}) gilt genauso als abgelaufen.
     */
    private boolean isBurntOut(World world, int x, int y, int z) {
        Toggles toggles = this.recent.get(key(x, y, z));
        if (toggles == null) return false;
        long verstrichen = world.getGameTime() - toggles.windowStart;
        if (verstrichen < 0 || verstrichen > BURNOUT_WINDOW) {
            this.recent.remove(key(x, y, z));   // Fenster durch: der Zähler ist wertlos
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
