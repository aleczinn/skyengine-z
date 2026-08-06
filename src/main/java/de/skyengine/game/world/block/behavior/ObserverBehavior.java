package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Beobachter: feuert einen 2-Tick-Puls (stark, aus der Rückseite), wenn sich der
 * Block-STATE der Zelle vor seinem Gesicht ändert. FACING zeigt zum beobachteten Block
 * (beim Platzieren in Blickrichtung — das Gesicht zeigt vom Spieler weg, wie MC).
 *
 * <p>Wie Vanillas {@code ObserverBlock#updateShape} reagiert er ausschließlich auf ein
 * gerichtetes Shape-Update aus seiner FACING-Richtung. Es gibt absichtlich keinen Vergleich
 * des Nachbar-States: Auch ein Update mit identischem State startet in Vanilla einen Puls.
 * Das Platzieren des Beobachters selbst erzeugt dagegen keinen Puls.
 */
public final class ObserverBehavior implements BlockBehavior {

    /**
     * Weckt ausschließlich Beobachter, deren Vorderseite auf die geänderte Zelle zeigt.
     * Zentraler Shape-Update-Ersatz für eine State-Änderung, die innerhalb eines laufenden
     * Nachbar-Recomputes entstanden ist. Nur der passende gerichtete Observer-Hook läuft;
     * fremde Behaviors werden dabei nicht ein zweites Mal allgemein aktualisiert.
     */
    public static void notifyWatching(World world, int x, int y, int z) {
        for (Direction direction : Direction.shapeUpdateValues()) {
            int ox = x + direction.offsetX();
            int oy = y + direction.offsetY();
            int oz = z + direction.offsetZ();
            BlockState observer = Blocks.getState(world.getBlock(ox, oy, oz));
            if (behaviorOf(observer.getId()) == null) continue;
            Direction towardChanged = direction.opposite();
            if (observer.get(Properties.FACING_ALL) != towardChanged) continue;
            observer.getBlock().getStateForShapeUpdate(world, ox, oy, oz, observer,
                    towardChanged, Blocks.getState(world.getBlock(x, y, z)));
        }
    }

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
    public BlockState onNeighborShapeUpdate(World world, int x, int y, int z, BlockState state,
                                            Direction direction, BlockState neighborState) {
        if (direction == state.get(Properties.FACING_ALL)
                && !state.get(Properties.POWERED)
                && !world.isTickScheduled(x, y, z)) {
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

    /**
     * Vanillas {@code affectNeighborsAfterRemoval}: Wird ein aktiver Puls vor seinem geplanten
     * Abschalt-Tick abgebaut, muss der bisher stark gespeiste Ausgangsblock noch die fallende
     * Flanke verteilen. Ein kuenstlich POWERED gesetzter Observer ohne Tick tut das bewusst nicht.
     */
    @Override
    public void onRemoved(World world, int x, int y, int z,
                          BlockState oldState, BlockState newState) {
        if (oldState.get(Properties.POWERED) && world.isTickScheduled(
                x, y, z, oldState.getBlock().getIdentifier())) {
            this.notifyStrongTarget(world, x, y, z, oldState.with(Properties.POWERED, false));
        }
    }

    /** Zweiter Ring um das stark gepowerte Ziel hinter dem Ausgang (Leitung durch den Block). */
    private void notifyStrongTarget(World world, int x, int y, int z, BlockState state) {
        Direction back = state.get(Properties.FACING_ALL).opposite();
        world.updateNeighbors(x + back.offsetX(), y + back.offsetY(), z + back.offsetZ());
    }

    /**
     * Von einem Kolben verschoben: Der Abschluss der Bewegung erzeugt in Vanilla die Shape-
     * Update-Kette, auf der Observer-Flugmaschinen beruhen. Bis der Moving-Piston-Abschluss
     * selbst vollständig auf Vanilla-Flags umgestellt ist, bildet dieser Hook die Flanke ab.
     */
    @Override
    public void onMovedByPiston(World world, int x, int y, int z, BlockState state, Direction moveDirection) {
        if (world.isTickScheduled(x, y, z)) return;
        if (state.get(Properties.POWERED)) {
            /* ObserverBlock#onPlace: Der Abschalt-Tick bleibt an der alten Position und wird
               nicht mit dem Block verschoben. Vanilla setzt einen so angekommenen aktiven
               Observer deshalb sofort aus und verteilt die fallende Ausgangsflanke. */
            BlockState unpowered = state.with(Properties.POWERED, false);
            world.setBlock(x, y, z, unpowered.getId(), false);
            this.notifyStrongTarget(world, x, y, z, unpowered);
            return;
        }
        world.scheduleTick(x, y, z, 2);
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

    @Override
    public boolean isRedstoneSignalSource() {
        return true;
    }

}
