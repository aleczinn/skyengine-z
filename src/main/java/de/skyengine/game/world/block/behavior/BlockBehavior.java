package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
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

    /** Abbau-Hook VOR dem Entfernen (andere Hälfte aufräumen, Inventar leeren, ...). Default: nichts. */
    default void onBreak(World world, int x, int y, int z, BlockState state) {
    }

    /**
     * Ersetzt beim Spieler-Abbau das Standard-Drop-Item ({@code Items.forBlock}) — z.B. droppt
     * der Kolben-Kopf das Item seiner Basis. Wird VOR {@link #onBreak} abgefragt (BlockEntity
     * noch lesbar) und läuft ausschließlich über den Gamemode-geprüften Pfad im GameContainer;
     * Behaviors dürfen Block-Drops deshalb NIE selbst spawnen ({@code onBreak} kennt keinen
     * Gamemode — Creative würde droppen). null = Standard-Drop.
     */
    default de.skyengine.game.world.item.ItemStack getDropOverride(World world, int x, int y, int z,
                                                                   BlockState state) {
        return null;
    }

    /**
     * Eine Entity-BoundingBox überlappt die Blockzelle (jeden Bewegungs-Tick, aus
     * {@code Entity.move}) — Druckplatte, später Seelensand-Bremse o.ä. Default: nichts.
     * {@code entity} ist die auslösende Entity (für Filter/Zählung der Sensor-Platten).
     */
    default void onEntityInside(World world, int x, int y, int z, BlockState state,
                                de.skyengine.game.entity.Entity entity) {
    }

    /**
     * Geplanter Tick (von {@code World.scheduleTick} ausgelöst): Fluss-Ausbreitung, Fallprüfung, ...
     * Default: nichts.
     *
     * <p><b>Konvention „tolerantes Feuern":</b> es gibt kein Abbestellen geplanter Ticks — wird
     * ein Block abgebaut und die Zelle neu bebaut, feuert der alte Tick am Nachfolger. Jede
     * Implementierung muss deshalb ihren State selbst validieren (z.B. {@code if (!state.get(
     * Properties.POWERED)) return;}) statt sich auf die Planung zu verlassen.</p>
     */
    default void scheduledTick(World world, int x, int y, int z, BlockState state) {
    }

    /** Zufalls-Tick (nur wenn der Block ticksRandomly meldet): Wachstum, Verfall, ... Default: nichts. */
    default void randomTick(World world, int x, int y, int z, BlockState state) {
    }

    /* --- Redstone (Abfragen laufen über RedstonePower, nie direkt über die Hooks) --- */

    /**
     * Schwaches Redstone-Signal 0..15, das dieser Block in Richtung {@code side} abgibt.
     * Konvention: {@code side} zeigt VOM Block ZUM Empfänger (Signalflussrichtung) —
     * ein Knopf an der Nordwand powert seinen Träger also mit {@code side == NORTH}.
     * Schwach heißt: wirkt auf direkte Nachbarn, wird aber von opaken Blöcken NICHT
     * weitergeleitet. Default 0.
     */
    default int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return 0;
    }

    /**
     * Starkes Redstone-Signal 0..15 in Richtung {@code side} (Konvention wie
     * {@link #weakPower}). Nur starke Signale machen einen opaken Block selbst zur
     * Quelle (Leitung durch Wände — Hebel am Block schaltet die Tür dahinter). Default 0.
     */
    default int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return 0;
    }

    /**
     * Verbindet sich Redstone-Staub optisch/logisch mit diesem Block, wenn er aus
     * Richtung {@code side} auf ihn schaut? (Hebel, Fackel, Verstärker-Enden, Staub
     * selbst.) Default false — Staub läuft an dem Block vorbei.
     */
    default boolean connectsRedstoneWire(BlockState state, Direction side) {
        return false;
    }
}
