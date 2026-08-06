package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstoneWireNetwork;

/**
 * Redstone-Staub: die Signal-Logik liegt im {@link RedstoneWireNetwork}. Jeder Weckruf berechnet
 * wie Vanillas DefaultRedstoneWireEvaluator nur die betroffene Zelle; weitere Staubzellen folgen
 * über die verschachtelte Nachbar-Update-Reihenfolge.
 *
 * <p>Signalabgabe (schwach UND stark, Vanilla): nach UNTEN immer, horizontal nur in
 * verbundene Richtungen, nach OBEN nie. „Stark" heißt: ein leitender Block, in den der Staub
 * einspeist, aktiviert seinerseits Nachbarn (Tür hinter der Wand) — dass darüber kein
 * Staub-zu-Staub-Signal läuft, verhindert der ignoreWire-Pfad in {@code RedstonePower}.
 */
public final class RedstoneWireBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    /**
     * Frisch platzierter Staub ist ein KREUZ (Vanillas {@code crossState} in
     * {@code getStateForPlacement}) — ohne Nachbarn speist er damit alle vier Seiten. Der
     * Property-Default wäre sonst überall NONE, also der Punkt, und der speist horizontal nichts.
     * Der Evaluator baut die Form danach an die Nachbarschaft an.
     */
    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return RedstoneWireNetwork.toCross(state);
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        RedstoneWireNetwork.update(world, x, y, z);
        /* RedStoneWireBlock.onPlace: zusaetzlich allgemeine Ringe unter und ueber dem Staub,
           danach die beiden horizontalen Corner-Wire-Paesse. */
        world.updateGeneralNeighborsAt(x, y - 1, z);
        world.updateGeneralNeighborsAt(x, y + 1, z);
        RedstoneWireNetwork.updateNeighborsOfNeighboringWires(world, x, y, z);
    }

    /**
     * Rechtsklick schaltet zwischen Kreuz und Punkt um (MCs {@code useWithoutItem}) — nur, wenn
     * der Staub gerade genau eines von beiden ist; eine Linie oder Ecke bleibt unberührt. Der
     * Punkt speist horizontal nichts, das Kreuz alle vier Nachbarn.
     */
    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        BlockState base;
        if (RedstoneWireNetwork.isCross(state)) {
            base = RedstoneWireNetwork.toDot(state);
        } else if (RedstoneWireNetwork.isDot(state)) {
            base = RedstoneWireNetwork.toCross(state);
        } else {
            return false;
        }
        BlockState updated = RedstoneWireNetwork.connectionState(world, x, y, z, base);
        if (updated == state) return false;

        /* Vanilla-Flag 3: normale Block-/Shape-Updates des eigentlichen State-Wechsels. */
        world.setBlock(x, y, z, updated.getId(), true);

        /* RedStoneWireBlock.updatesOnShapeChange: Nur geänderte Anschlussseiten lösen den
           zusätzlichen Ring aus, nur wenn die angrenzende Zelle leitend ist, und dort ohne
           die zum Staub zurückweisende Richtung. */
        for (Direction direction : Direction.horizontalValues()) {
            boolean wasConnected = state.get(Properties.wireSide(direction)).isConnected();
            boolean isConnected = updated.get(Properties.wireSide(direction)).isConnected();
            if (wasConnected == isConnected) continue;
            int nx = x + direction.offsetX(), nz = z + direction.offsetZ();
            if (!de.skyengine.game.world.block.Blocks.getState(
                    world.getBlock(nx, y, nz)).isRedstoneConductor()) continue;
            world.updateGeneralNeighborsAtExceptFromFacing(
                    nx, y, nz, direction.opposite());
        }
        return true;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        /* State unverändert zurück: der Evaluator schreibt selbst (auch die eigene Zelle) —
           so bleibt der Pull-Vertrag von updateStateAt formal erfüllt, kein Doppel-Write. */
        RedstoneWireNetwork.update(world, x, y, z);
        return state;
    }

    @Override
    public void onRemoved(World world, int x, int y, int z,
                          BlockState state, BlockState newState) {
        /* RedStoneWireBlock.affectNeighborsAfterRemoval: alle Phasen laufen sofort und in
           dieser Reihenfolge, nachdem die Welt bereits den Nachfolgezustand enthält. */
        world.updateGeneralNeighborsAroundAdjacentCells(x, y, z);
        RedstoneWireNetwork.updateAfterRemoval(world, x, y, z, state);
        RedstoneWireNetwork.updateNeighborsOfNeighboringWires(world, x, y, z);
    }

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return signalToward(state, side);
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return signalToward(state, side);
    }

    private static int signalToward(BlockState state, Direction side) {
        int power = state.get(Properties.POWER);
        if (power == 0) return 0;
        if (side == Direction.DOWN) return power;
        if (side == Direction.UP) return 0;
        return state.get(Properties.wireSide(side)).isConnected() ? power : 0;
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
