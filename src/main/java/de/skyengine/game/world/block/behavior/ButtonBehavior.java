package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Knopf: Rechtsklick drückt ihn, nach {@code pressTicks} springt er von selbst zurück.
 *
 * <p>Läuft zusammen mit dem {@link AttachBehavior}, das die Trägerfläche bestimmt. Ergänzt wird
 * hier nur, was der Fackel fehlt: bei FLOOR/CEILING dreht sich der Knopf zum Spieler (in Vanilla
 * bestimmt {@code facing} dort die Ausrichtung des Modells, für die Fackel war es bedeutungslos).
 *
 * <p>Die Verzögerung steht als {@code press_ticks} in der Block-JSON — Stein 20, Holz 30 Ticks
 * sind aus dem Gedächtnis übernommen und aus den MC-Assets NICHT belegbar, weil sie in MCs
 * Java-Code stehen. Deshalb datengetrieben statt hartkodiert.
 */
public final class ButtonBehavior implements BlockBehavior {

    private final int pressTicks;

    public ButtonBehavior(int pressTicks) {
        this.pressTicks = Math.max(1, pressTicks);
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        BlockState placed = state.with(Properties.POWERED, false);
        /* AttachBehavior hat die Fläche schon gesetzt; am Boden/an der Decke fehlt nur die
           Blickrichtung. An der Wand hat es FACING bereits aus der Normale bestimmt. */
        if (placed.get(Properties.ATTACH) == AttachFace.WALL) return placed;
        return placed.with(Properties.FACING, Direction.fromYaw(ctx.playerYaw()).opposite());
    }

    @Override
    public boolean onUse(Dimension world, int x, int y, int z, BlockState state) {
        if (state.get(Properties.POWERED)) return true;   // gedrückt: Klick verbraucht, sonst nichts

        /* true = Nachbar-Update, sonst erführe die Tür nebenan nichts davon. */
        world.setBlock(x, y, z, state.with(Properties.POWERED, true).getId(), true);
        world.scheduleTick(x, y, z, this.pressTicks);
        notifyStrongTarget(world, x, y, z, state);
        return true;
    }

    @Override
    public void scheduledTick(Dimension world, int x, int y, int z, BlockState state) {
        if (!state.get(Properties.POWERED)) return;
        world.setBlock(x, y, z, state.with(Properties.POWERED, false).getId(), true);
        notifyStrongTarget(world, x, y, z, state);
    }

    @Override
    public void onRemoved(Dimension world, int x, int y, int z,
                          BlockState oldState, BlockState newState) {
        if (oldState.get(Properties.POWERED)) notifyStrongTarget(world, x, y, z, oldState);
    }

    /* --- Redstone: gedrückt = 15 in alle Richtungen (schwach), stark nur in den Träger --- */

    @Override
    public int weakPower(Dimension world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED) ? 15 : 0;
    }

    @Override
    public int strongPower(Dimension world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED) && side == supportDirection(state) ? 15 : 0;
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return true;
    }

    @Override
    public boolean isRedstoneSignalSource() {
        return true;
    }

    /** Richtung vom Knopf ZUM Träger (der stark gepowert wird). */
    static Direction supportDirection(BlockState state) {
        return switch (state.get(Properties.ATTACH)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> state.get(Properties.FACING).opposite();
        };
    }

    /**
     * Zweiter Nachbar-Ring um den stark gepowerten Träger: nur so erfährt eine Tür, die am
     * selben Block hängt wie der Knopf, von der Flanke (Leitung durch den Block).
     */
    static void notifyStrongTarget(Dimension world, int x, int y, int z, BlockState state) {
        Direction d = supportDirection(state);
        world.updateGeneralNeighborsAt(x, y, z);
        world.updateGeneralNeighborsAt(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
    }
}
