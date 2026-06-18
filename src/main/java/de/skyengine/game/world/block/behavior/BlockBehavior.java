package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Komponierbares Block-Verhalten (Komposition statt Vererbung). Ein Block kombiniert
 * beliebig viele Behaviors; jeder Hook transformiert den State. Default = no-op.
 */
public interface BlockBehavior {

    /** Beim Platzieren: liefert den anzulegenden State (Facing, Slab-Hälfte, ...). Rein, ohne Welt-Mutation. */
    default BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state;
    }

    /**
     * Veto VOR der Platzierung: false bricht das Setzen ab (z.B. Tür ohne Platz darüber).
     * Wird mit dem von {@link #onPlace} berechneten State aufgerufen. Default: erlaubt.
     */
    default boolean canPlace(PlacementContext ctx, BlockState state) {
        return true;
    }

    /**
     * Seiteneffekte NACH erfolgreicher Platzierung (z.B. zweite Block-Hälfte setzen).
     * Hier ist der eigene Block bereits gesetzt und validiert. Default: nichts.
     */
    default void onPlaced(World world, int x, int y, int z, BlockState state) {
    }

    /** Nach Nachbaränderung: liefert den ggf. angepassten State (Verbindungen, Ecken). */
    default BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        return state;
    }

    /** Rechtsklick auf den Block. true = verbraucht (kein Platzieren). Default: ignoriert. */
    default boolean onUse(World world, int x, int y, int z, BlockState state) {
        return false;
    }

    /** Abbau-Hook VOR dem Entfernen (Drops, andere Hälfte aufräumen, ...). Default: nichts. */
    default void onBreak(World world, int x, int y, int z, BlockState state) {
    }

    /** Geplanter Tick (von {@code World.scheduleTick} ausgelöst): Fluss-Ausbreitung, Fallprüfung, ... Default: nichts. */
    default void scheduledTick(World world, int x, int y, int z, BlockState state) {
    }

    /** Zufalls-Tick (nur wenn der Block ticksRandomly meldet): Wachstum, Verfall, ... Default: nichts. */
    default void randomTick(World world, int x, int y, int z, BlockState state) {
    }
}
