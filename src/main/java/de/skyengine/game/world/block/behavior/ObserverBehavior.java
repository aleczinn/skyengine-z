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
        /* Vanilla-Flag 2: kein allgemeiner Nachbarring; der gerichtete Shape-Ring bleibt
           erhalten und macht die Flanke insbesondere fuer einen zweiten Observer sichtbar. */
        world.setBlockWithShapeUpdates(
                x, y, z, state.with(Properties.POWERED, !powered).getId());
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
        /* The scheduled OFF tick stays at the old coordinate while the observer moves.
           Emitting the falling edge here would retract the piston before the complete
           slime assembly has materialized and turns the pulse into TRIGGER_DROP. */
        if (world.isPistonBlockMove()) return;
        if (oldState.get(Properties.POWERED) && world.isTickScheduled(
                x, y, z, oldState.getBlock().getIdentifier())) {
            this.notifyStrongTarget(world, x, y, z, oldState.with(Properties.POWERED, false));
        }
    }

    /** Zweiter Ring um das stark gepowerte Ziel hinter dem Ausgang (Leitung durch den Block). */
    private void notifyStrongTarget(World world, int x, int y, int z, BlockState state) {
        Direction back = state.get(Properties.FACING_ALL).opposite();
        world.updateDirectionalOutputNeighbors(x, y, z, back);
    }

    /**
     * Beim Verschieben bleibt ein geplanter Tick an der alten Position. Nur ein aktiv
     * ankommender Observer muss deshalb wie in Vanilla sofort ausgeschaltet werden. Den
     * normalen Ankunftspuls erzeugt der anschließende gerichtete Shape-Pass der Welt.
     */
    @Override
    public void onMovedByPiston(World world, int x, int y, int z, BlockState state, Direction moveDirection) {
        if (world.isTickScheduled(x, y, z)) return;
        if (state.get(Properties.POWERED)) {
            /* ObserverBlock#onPlace: Der Abschalt-Tick bleibt an der alten Position und wird
               nicht mit dem Block verschoben. Vanilla setzt einen so angekommenen aktiven
               Observer deshalb sofort aus und verteilt die fallende Ausgangsflanke. */
            BlockState unpowered = state.with(Properties.POWERED, false);
            world.setBlockWithShapeUpdates(x, y, z, unpowered.getId());
            /* Vanilla's pending tick was tied to the old coordinate. Once the whole piston
               group has landed, distribute that falling edge from the former output so the
               wire which launched the movement can retract the piston normally. */
            int oldX = x - moveDirection.offsetX();
            int oldY = y - moveDirection.offsetY();
            int oldZ = z - moveDirection.offsetZ();
            this.notifyStrongTarget(world, oldX, oldY, oldZ, unpowered);
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

    @Override
    public boolean isRedstoneSignalSource() {
        return true;
    }

}
