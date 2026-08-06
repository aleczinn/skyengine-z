package de.skyengine.game.world.block.behavior;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.function.Predicate;

/**
 * Druckplatte mit Sensor: reagiert nur auf gefilterte Entities und gibt entweder ein
 * binäres Signal (POWERED, Stein/Holz) oder eines nach Anzahl (POWER 0..15, Wägeplatten —
 * Gold: 1 Entity je Stufe, Eisen: 10). Konfiguration aus der {@code sensor}-Sektion der
 * Block-JSON ({@code BlockDefinition.SensorDef}).
 *
 * <p><b>Warum Zähl-Akkumulation und keine Box-Abfrage:</b> es gibt keine „welche Entities
 * stecken in dieser Box"-Abfrage. Jede passende Entity meldet sich stattdessen jeden Tick
 * über {@code onEntityInside} (alle Entity-Typen rufen {@code move()} auch im Stillstand);
 * die Platte zählt die Berührungen des laufenden Ticks pro Position. Die Aktivierung
 * (Signal 0 → &gt;0) wirkt sofort, Anpassungen im gedrückten Zustand (Wägeplatte: 5 Items
 * → 3) übernimmt der laufende Tick-Takt — wie MCs 10-Tick-Rekalkulation. Der Zähler ist
 * bewusst transient: er überlebt keinen Neustart, der geplante Tick aber schon
 * (Tick-Persistenz v2) — eine beim Beenden gedrückte Platte fällt danach von selbst zurück.
 */
public final class PressurePlateBehavior implements BlockBehavior {

    /** Berührungen einer Position im laufenden Tick (Reset beim Gametime-Wechsel). */
    private static final class Touch {
        long time;
        int count;
    }

    private final int releaseTicks;
    private final Predicate<Entity> filter;
    /** true = Signal nach Anzahl über POWER (16 States), false = binär über POWERED. */
    private final boolean counting;
    private final int minCount;
    private final int perSignal;

    /** Welt- und Chunk-gebundene Berührungszähler. Nur Tick-Thread, deshalb ungesichert. */
    private final WorldScopedPositionMap<Touch> touches = new WorldScopedPositionMap<>();

    public PressurePlateBehavior(int releaseTicks, Predicate<Entity> filter,
                                 boolean counting, int minCount, int perSignal) {
        this.releaseTicks = Math.max(1, releaseTicks);
        this.filter = filter;
        this.counting = counting;
        this.minCount = Math.max(1, minCount);
        this.perSignal = Math.max(1, perSignal);
    }

    @Override
    public void onEntityInside(World world, int x, int y, int z, BlockState state, Entity entity) {
        if (!this.filter.test(entity)) return;

        long now = world.getGameTime();
        Touch touch = this.touches.computeIfAbsent(world, x, y, z, Touch::new);
        if (touch.time != now) {
            touch.time = now;
            touch.count = 0;
        }
        touch.count++;

        /* Aktivierung sofort (wie MC); alles Weitere macht der Tick-Takt unten. */
        if (signalOf(state) == 0) {
            int signal = this.signalFor(touch.count);
            if (signal > 0) {
                this.applySignal(world, x, y, z, state, signal);
                world.scheduleTick(x, y, z, this.releaseTicks);
            }
        }
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        int current = signalOf(state);
        if (current == 0) return;   // tolerantes Feuern (z.B. Tick am Nachfolge-Block)

        Touch touch = this.touches.get(world, x, y, z);
        int count = touch != null && world.getGameTime() - touch.time <= 1 ? touch.count : 0;
        int signal = this.signalFor(count);
        if (signal != current) this.applySignal(world, x, y, z, state, signal);
        if (signal > 0) {
            world.scheduleTick(x, y, z, this.releaseTicks);   // steht noch jemand drauf
        } else {
            this.touches.remove(world, x, y, z);
        }
    }

    @Override
    public void onRemoved(World world, int x, int y, int z,
                          BlockState oldState, BlockState newState) {
        this.touches.remove(world, x, y, z);
        if (signalOf(oldState) > 0) this.notifyNeighbors(world, x, y, z);
    }

    /** Signalstärke für N passende Entities: binär 15, sonst ceil(n/perSignal), gedeckelt 15. */
    private int signalFor(int count) {
        if (count < this.minCount) return 0;
        if (!this.counting) return 15;
        return Math.min(15, (count + this.perSignal - 1) / this.perSignal);
    }

    /** Schreibt das Signal in POWERED bzw. POWER + zweiter Ring um den stark gepowerten Träger. */
    private void applySignal(World world, int x, int y, int z, BlockState state, int signal) {
        BlockState updated = this.counting
                ? state.with(Properties.POWER, signal)
                : state.with(Properties.POWERED, signal > 0);
        /* Vanilla-Flag 2; die beiden allgemeinen Ringe folgen explizit. */
        world.setBlockWithShapeUpdates(x, y, z, updated.getId());
        this.notifyNeighbors(world, x, y, z);
    }

    private void notifyNeighbors(World world, int x, int y, int z) {
        world.updateGeneralNeighborsAt(x, y, z);
        world.updateGeneralNeighborsAt(x, y - 1, z);
    }

    private int signalOf(BlockState state) {
        return this.counting
                ? state.get(Properties.POWER)
                : state.get(Properties.POWERED) ? 15 : 0;
    }

    /* --- Redstone: Signalwert in alle Richtungen (schwach), stark nur nach unten --- */

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return signalOf(state);
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return side == Direction.DOWN ? signalOf(state) : 0;
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
