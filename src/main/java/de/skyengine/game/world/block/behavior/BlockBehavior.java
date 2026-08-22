package de.skyengine.game.world.block.behavior;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.TooltipContext;
import java.util.Map;
import java.util.Random;

/**
 * Komponierbares Block-Verhalten (Komposition statt Vererbung). Ein Block kombiniert
 * beliebig viele Behaviors; jeder Hook transformiert den State. Default = no-op.
 */
public interface BlockBehavior {

    /** Benannte Laufzeitwerte für Platzhalter in der Beschreibung des BlockItems. */
    default void appendTooltipVariables(ItemStack stack, TooltipContext context,
                                        Map<String, String> variables) {
    }

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

    /**
     * Der Block wurde soeben von einem Kolben an dieser Zelle ABGESETZT (MCs
     * {@code movedByPiston}-Flag). Läuft NACH dem Schreiben, aber VOR dem Nachbar-Ring, damit
     * dessen Selbst-Update den neuen Zustand schon sieht. Bewusst getrennt von
     * {@link #onPlaced}: Verschieben ist kein Platzieren — eine Tür z.B. darf dabei nicht
     * ihre zweite Hälfte nachsetzen.
     *
     * @param moveDirection Richtung, in die der Block bewegt wurde (Quellzelle = Position
     *                      minus diese Richtung).
     */
    default void onMovedByPiston(World world, int x, int y, int z, BlockState state, Direction moveDirection) {
    }

    /** Nach Nachbaränderung: liefert den ggf. angepassten State (Verbindungen, Ecken). */
    default BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        return state;
    }

    /**
     * Gerichtetes Shape-Update eines unmittelbaren Nachbarn. {@code direction} zeigt von
     * diesem Block zu der Zelle, die das Update ausgelöst hat. Das entspricht der Richtung,
     * die Vanilla an {@code Block#updateShape} übergibt.
     */
    default BlockState onNeighborShapeUpdate(World world, int x, int y, int z, BlockState state,
                                             Direction direction, BlockState neighborState) {
        return state;
    }

    /**
     * Blockeigener Seiteneffekt NACHDEM ein {@link #onNeighborUpdate}-Ergebnis geschrieben wurde.
     * Die allgemeine Observer-Benachrichtigung erfolgt anschließend zentral in {@code Block}.
     */
    default void onStateChangedByNeighborUpdate(World world, int x, int y, int z,
                                                BlockState oldState, BlockState newState) {
    }

    /** Rechtsklick auf den Block. true = verbraucht (kein Platzieren). Default: ignoriert. */
    default boolean onUse(World world, int x, int y, int z, BlockState state) {
        return false;
    }

    /** Rechtsklick mit horizontaler Spieler-Blickrichtung; bestehende Behaviors bleiben kompatibel. */
    default boolean onUse(World world, int x, int y, int z, BlockState state, float playerYaw) {
        return this.onUse(world, x, y, z, state);
    }

    /** Abbau-Hook VOR dem Entfernen (andere Hälfte aufräumen, Inventar leeren, ...). Default: nichts. */
    default void onBreak(World world, int x, int y, int z, BlockState state) {
    }

    /**
     * Seiteneffekt NACH einem Blocktypwechsel, wenn die alte Zelle bereits ersetzt ist.
     * Entspricht Vanillas {@code affectNeighborsAfterRemoval}; Redstone-Ausgaenge muessen hier
     * die fallende Flanke verteilen, damit ihre Empfaenger nicht mehr den alten Block lesen.
     */
    default void onRemoved(World world, int x, int y, int z,
                           BlockState oldState, BlockState newState) {
    }

    /** Ergänzt dynamische Drops (Inventarinhalte, Kolbenbasis) zur statischen Loot-Tabelle. */
    default void appendDrops(LootContext context,
                             LootSink sink) {
    }

    /** Kanonischer Drop-Ursprung für Batch-Zerstörung; Standard ist die aktuelle Zelle. */
    default long canonicalLootPosition(LootContext context) {
        return BlockPos.asLong(context.x(), context.y(), context.z());
    }

    /**
     * Darf dieser Block beim Selbstabbau durch ein Nachbar-/Support-Update Loot erzeugen?
     * Mehrteilige Blöcke verneinen das: Der vom Spieler bzw. der Explosion getroffene Teil hat
     * den gemeinsamen Drop bereits ausgewertet, die automatisch entfernte Hälfte ist nur Cleanup.
     */
    default boolean dropsWhenUnsupported() {
        return true;
    }

    /**
     * Eine Entity-BoundingBox überlappt die Blockzelle (jeden Bewegungs-Tick, aus
     * {@code Entity.move}) — Druckplatte, später Seelensand-Bremse o.ä. Default: nichts.
     * {@code entity} ist die auslösende Entity (für Filter/Zählung der Sensor-Platten).
     */
    default void onEntityInside(World world, int x, int y, int z, BlockState state,
                                Entity entity) {
    }

    /**
     * Geplanter Tick (von {@code World.scheduleTick} ausgelöst): Fluss-Ausbreitung, Fallprüfung, ...
     * Default: nichts.
     *
     * <p><b>Konvention „tolerantes Feuern":</b> der Scheduler bindet einen Tick an den Blocktyp
     * und verwirft ihn nach einem Typwechsel. Zustände desselben Blocks können sich bis zum
     * Feuern aber ändern; jede Implementierung validiert deshalb weiterhin ihren State.</p>
     */
    default void scheduledTick(World world, int x, int y, int z, BlockState state) {
    }

    /** Zufalls-Tick (nur wenn der Block ticksRandomly meldet): Wachstum, Verfall, ... Default: nichts. */
    default void randomTick(World world, int x, int y, int z, BlockState state) {
    }

    /**
     * Clientseitiger Zufalls-/Animations-Tick nahe am Spieler. Darf nur kosmetische Effekte
     * auslösen (Sounds, später Partikel), niemals persistente Weltzustände verändern.
     */
    default void animateTick(World world, int x, int y, int z, BlockState state, Random random) {
    }

    /**
     * Block-Event (s. {@code World.enqueueBlockEvent}): läuft im SELBEN Game-Tick wie die
     * auslösende Flanke, aber außerhalb der Nachbar-Update-Kaskade — der Ort für schwere
     * Multi-Block-Aktionen (Kolben). {@code eventId} bestimmt die Aktion, {@code eventParam}
     * trägt blockspezifische Zusatzdaten. Tolerantes Feuern wie beim Tick-Scheduler. Default: nichts.
     */
    default void onBlockEvent(World world, int x, int y, int z, BlockState state,
                              int eventId, int eventParam) {
    }

    /* --- Redstone (Abfragen laufen über RedstonePower, nie direkt über die Hooks) --- */

    /**
     * Schwaches Redstone-Signal 0..15, das dieser Block in Richtung {@code side} abgibt.
     * Konvention: {@code side} zeigt VOM Block ZUM Empfänger (Signalflussrichtung) —
     * ein Knopf an der Nordwand powert seinen Träger also mit {@code side == NORTH}.
     * Schwach heißt: wirkt auf direkte Nachbarn, wird aber von Redstone-Leitern NICHT
     * weitergeleitet. Default 0.
     */
    default int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return 0;
    }

    /**
     * Starkes Redstone-Signal 0..15 in Richtung {@code side} (Konvention wie
     * {@link #weakPower}). Nur starke Signale machen einen leitenden Block selbst zur
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

    /**
     * Der Block ist selbst eine Redstone-Signalquelle. Vanillas Comparator-Seiteneingang
     * akzeptiert solche Quellen, aber keine nur indirekt gespeisten leitenden Vollblöcke.
     */
    default boolean isRedstoneSignalSource() {
        return false;
    }

    /**
     * true für Redstone-Empfänger, deren gespeicherter Zustand beim Laden oder Entladen eines
     * Nachbar-Chunks einmal gegen die veränderte Kante abgeglichen werden muss.
     * Quellen und Beobachter bleiben false: ihre Zustände sind persistiert bzw. werden
     * separat ohne Phantomflanke initialisiert.
     */
    default boolean reconcileRedstoneOnChunkBoundary() {
        return false;
    }
}
