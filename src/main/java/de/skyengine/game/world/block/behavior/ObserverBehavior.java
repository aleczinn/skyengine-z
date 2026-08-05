package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.PalettedContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * Beobachter: feuert einen 2-Tick-Puls (stark, aus der Rückseite), wenn sich der
 * Block-STATE der Zelle vor seinem Gesicht ändert. FACING zeigt zum beobachteten Block
 * (beim Platzieren in Blickrichtung — das Gesicht zeigt vom Spieler weg, wie MC).
 *
 * <p><b>Änderungs-Erkennung ohne gerichteten Hook:</b> {@code onNeighborUpdate} kennt den
 * Auslöser nicht — der Beobachter vergleicht deshalb die beobachtete Zelle mit dem zuletzt
 * gesehenen State (transiente Map, Muster PressurePlate). Erstkontakt (Platzieren,
 * Chunk-Load) speichert nur, OHNE Puls — bewusste Abweichung von MCs
 * Chunk-Load-Feuern.
 */
public final class ObserverBehavior implements BlockBehavior {

    /** Welt- und Chunk-gebundene Vergleichsbasis; die Diagnose-Sicht bleibt sondierbar. */
    private final WorldScopedPositionMap<Integer> observedByWorld = new WorldScopedPositionMap<>();
    @SuppressWarnings("FieldMayBeFinal") // Referenz wechselt mit der aktiven Welt (Headless-Diagnose).
    private Map<Long, ?> observed = new HashMap<>();

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING_ALL, lookDirection(ctx))
                .with(Properties.POWERED, false);
    }

    /** Blickrichtung des Spielers (Gesicht zeigt vom Spieler WEG — invers zum Kolben). */
    private static Direction lookDirection(PlacementContext ctx) {
        if (ctx.playerPitch() > 45) return Direction.DOWN;
        if (ctx.playerPitch() < -45) return Direction.UP;
        return Direction.fromYaw(ctx.playerYaw());
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        /* Initialen Zustand merken — die erste echte Änderung soll pulsen, nicht das Platzieren. */
        this.remember(world, x, y, z, watchedState(world, x, y, z, state));
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        int watched = watchedState(world, x, y, z, state);
        Integer last = this.remember(world, x, y, z, watched);
        if (last != null && last != watched && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, 2);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        boolean powered = state.get(Properties.POWERED);
        world.setBlock(x, y, z, state.with(Properties.POWERED, !powered).getId(), true);
        this.notifyStrongTarget(world, x, y, z, state);
        if (!powered) world.scheduleTick(x, y, z, 2);   // Puls-Ende nach 2 Ticks
    }

    /** Zweiter Ring um das stark gepowerte Ziel hinter dem Ausgang (Leitung durch den Block). */
    private void notifyStrongTarget(World world, int x, int y, int z, BlockState state) {
        Direction back = state.get(Properties.FACING_ALL).opposite();
        world.updateNeighbors(x + back.offsetX(), y + back.offsetY(), z + back.offsetZ());
    }

    /**
     * Von einem Kolben verschoben: In MC pulst ein bewegter Beobachter — genau darauf beruhen
     * Flugmaschinen. Hier fehlt ihm an der neuen Zelle sonst jede Vorgeschichte
     * ({@code last == null} in {@link #onNeighborUpdate}) und er bliebe stumm.
     *
     * <p>Der Eintrag der Quellzelle muss dabei weg: Kolben räumen ihre Quellen ohne
     * {@code onBreak}, der Eintrag bliebe also liegen — bei einer Flugmaschine wächst die Map
     * sonst nicht nur endlos, sie vergleicht beim zyklischen Zurückkehren auf eine früher
     * besetzte Zelle auch gegen einen veralteten Wert.
     */
    @Override
    public void onMovedByPiston(World world, int x, int y, int z, BlockState state, Direction moveDirection) {
        this.forget(world, x - moveDirection.offsetX(), y - moveDirection.offsetY(),
                z - moveDirection.offsetZ());
        this.remember(world, x, y, z, watchedState(world, x, y, z, state));
        if (!world.isTickScheduled(x, y, z)) world.scheduleTick(x, y, z, 2);
    }

    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        this.forget(world, x, y, z);
    }

    /**
     * Stellt nach dem Laden eines Chunks die Vergleichsbasis aller darin stehenden Beobachter
     * wieder her — ohne Puls und ohne geplanten Tick.
     *
     * <p>Die Map ist transient, nach dem Laden also leer. Ohne diesen Durchlauf frisst der
     * Erstkontakt-Zweig in {@link #onNeighborUpdate} genau EINE echte Flanke je Beobachter und
     * Ladevorgang: ein einzelner Beobachter ignoriert die erste Änderung, und eine Clock reißt
     * ab, weil der Partner den restaurierten Puls stumm wegsteckt.
     *
     * <p>Der laufende Zustand kommt derweil aus Quellen, die den Save überleben — {@code POWERED}
     * steht im BlockState, der Rest-Delay des 2-Tick-Pulses in den gespeicherten Ticks. Hier wird
     * NUR die Vergleichsbasis nachgezogen, damit die Clock in ihrer Phase weiterläuft, statt
     * einen bereits gelaufenen Schritt zu wiederholen.
     *
     * <p>Aufrufzeitpunkt ist bewusst „Chunk wurde READY" ({@code World.processReadyChunks}): das
     * Gate des Mesh-Jobs garantiert alle 8 Nachbarn auf mindestens LIT, erst dann liefert
     * {@code World.getBlock} über die Chunk-Grenze echte Daten. Früher gelesen, merkte sich ein
     * Beobachter an der Kante still Luft — und löste beim nächsten Update einen Phantom-Puls aus.
     *
     * <p>Kosten: Ein Zell-Scan wäre teuer (32768 je Section), deshalb wie
     * {@code LightEngine.seedEmitters} zuerst der Paletten-Vorfilter. In normalem Gelände fällt
     * jede Section heraus, ohne dass eine einzige Zelle gelesen wird.
     */
    public static void seedLoadedChunk(World world, Chunk chunk) {
        int originX = chunk.chunkX << ChunkSection.SHIFT;
        int originZ = chunk.chunkZ << ChunkSection.SHIFT;

        for (int s = 0; s < Chunk.SECTIONS; s++) {
            ChunkSection section = chunk.getSection(s);
            if (section == null || section.isEmpty()) continue;
            PalettedContainer container = section.container();
            if (container == null) continue;

            boolean anyObserver = false;
            for (int stateId : container.paletteEntries()) {
                if (stateId != 0 && behaviorOf(stateId) != null) {
                    anyObserver = true;
                    break;
                }
            }
            if (!anyObserver) continue;

            int base = s << ChunkSection.SHIFT;
            for (int ly = 0; ly < ChunkSection.SIZE; ly++) {
                for (int lz = 0; lz < ChunkSection.SIZE; lz++) {
                    for (int lx = 0; lx < ChunkSection.SIZE; lx++) {
                        int id = section.getBlock(lx, ly, lz);
                        if (id == 0) continue;
                        ObserverBehavior behavior = behaviorOf(id);
                        if (behavior == null) continue;
                        int x = originX + lx, y = base + ly, z = originZ + lz;
                        behavior.remember(world, x, y, z,
                                watchedState(world, x, y, z, BlockRegistry.getState(id)));
                    }
                }
            }
        }
    }

    /** Die Behavior-Instanz hinter einer State-ID, oder null wenn das kein Beobachter ist. */
    private static ObserverBehavior behaviorOf(int stateId) {
        return BlockRegistry.getState(stateId).getBlock().getBehavior(ObserverBehavior.class);
    }

    /* --- Ausgang: 15 stark UND schwach, nur aus der Rückseite --- */

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED)
                && side == state.get(Properties.FACING_ALL).opposite() ? 15 : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return weakPower(world, x, y, z, state, side);
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return side == state.get(Properties.FACING_ALL).opposite();
    }

    private static int watchedState(World world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        return world.getBlock(x + f.offsetX(), y + f.offsetY(), z + f.offsetZ());
    }

    private Integer remember(World world, int x, int y, int z, int stateId) {
        this.observed = this.observedByWorld.diagnosticEntries(world);
        return this.observedByWorld.put(world, x, y, z, stateId);
    }

    private void forget(World world, int x, int y, int z) {
        this.observed = this.observedByWorld.diagnosticEntries(world);
        this.observedByWorld.remove(world, x, y, z);
    }
}
