package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstoneWireNetwork;

/**
 * Redstone-Staub: die Signal-Logik liegt komplett im {@link RedstoneWireNetwork} — dieses
 * Behavior ist nur der Weckruf (Muster FluidBehavior: onNeighborUpdate stößt an, schreibt
 * aber nicht selbst) plus die Power-Hooks für Nicht-Staub-Empfänger.
 *
 * <p>Signalabgabe (schwach UND stark, Vanilla): nach UNTEN immer, horizontal nur in
 * verbundene Richtungen, nach OBEN nie. „Stark" heißt: ein opaker Block, in den der Staub
 * einspeist, aktiviert seinerseits Nachbarn (Tür hinter der Wand) — dass darüber kein
 * Staub-zu-Staub-Signal läuft, verhindert der ignoreWire-Pfad in {@code RedstonePower}.
 */
public final class RedstoneWireBehavior implements BlockBehavior {

    /**
     * Frisch platzierter Staub ist ein KREUZ (Vanillas {@code crossState} in
     * {@code getStateForPlacement}) — ohne Nachbarn speist er damit alle vier Seiten. Der
     * Property-Default wäre sonst überall NONE, also der Punkt, und der speist horizontal nichts.
     * Die Normalisierung im Netz baut die Form danach an die Nachbarschaft an.
     */
    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return RedstoneWireNetwork.toCross(state);
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        RedstoneWireNetwork.update(world, x, y, z);
    }

    /**
     * Rechtsklick schaltet zwischen Kreuz und Punkt um (MCs {@code useWithoutItem}) — nur, wenn
     * der Staub gerade genau eines von beiden ist; eine Linie oder Ecke bleibt unberührt. Der
     * Punkt speist horizontal nichts, das Kreuz alle vier Nachbarn.
     */
    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        BlockState umgeschaltet;
        if (RedstoneWireNetwork.isCross(state)) {
            umgeschaltet = RedstoneWireNetwork.toDot(state);
        } else if (RedstoneWireNetwork.isDot(state)) {
            umgeschaltet = RedstoneWireNetwork.toCross(state);
        } else {
            return false;
        }
        world.setBlock(x, y, z, umgeschaltet.getId(), false);
        /* Das Netz schreibt die endgültige Form selbst und benachrichtigt die Empfänger — der
           Umschalter ändert ja, wen dieser Staub speist. */
        RedstoneWireNetwork.update(world, x, y, z);
        world.updateNeighbors(x, y, z);
        return true;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        /* State unverändert zurück: das Netz schreibt selbst (auch die eigene Zelle) —
           so bleibt der Pull-Vertrag von updateStateAt formal erfüllt, kein Doppel-Write. */
        RedstoneWireNetwork.update(world, x, y, z);
        return state;
    }

    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        /* Post-Removal-2-Ring (Vanillas onRemove): Empfänger HINTER einem stark gespeisten
           Block (Tür an der Rückseite der Wand, 2 Zellen entfernt) stehen nicht im normalen
           Abbau-Ring und erführen vom Entfernen nie. deferBlockUpdate re-evaluiert die
           Positionen im NÄCHSTEN Tick — also nach dem Entfernen. */
        for (Direction d : strongTargets(state)) {
            int tx = x + d.offsetX(), ty = y + d.offsetY(), tz = z + d.offsetZ();
            if (!Blocks.getState(world.getBlock(tx, ty, tz)).isOpaqueCube()) continue;
            for (Direction n : Direction.values()) {
                world.deferBlockUpdate(tx + n.offsetX(), ty + n.offsetY(), tz + n.offsetZ());
            }
        }
    }

    /** Richtungen, in die dieser State stark einspeist: unten immer, verbundene Seiten dazu. */
    private static Direction[] strongTargets(BlockState state) {
        int count = 1;
        Direction[] targets = new Direction[5];
        targets[0] = Direction.DOWN;
        for (Direction d : Direction.horizontalValues()) {
            if (state.get(Properties.wireSide(d)).isConnected()) targets[count++] = d;
        }
        Direction[] out = new Direction[count];
        System.arraycopy(targets, 0, out, 0, count);
        return out;
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
}
