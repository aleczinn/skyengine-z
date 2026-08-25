package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ChestType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;

/**
 * Ausrichtung, Verschmelzen zur Doppeltruhe und Auftrennen — nachgebildet nach MCs
 * {@code ChestBlock} (getStateForPlacement/updateShape).
 *
 * <p>Verbunden wird beim Platzieren neben einer gleich ausgerichteten Truhe; <b>Sneaken
 * verhindert</b> das. Ausnahme: ein sneakender Klick auf die <b>Seite</b> einer Truhe verbindet
 * trotzdem und übernimmt deren Ausrichtung.
 *
 * <p>Eine Doppeltruhe sind bewusst ZWEI eigenständige Blöcke mit je eigenem BlockEntity und
 * eigenem Inventar; zusammen gehören sie nur über {@code facing} + {@code type}. Deshalb ist
 * hier weder {@code PartsBehavior} (setzt/löscht die Nachbarzelle) noch
 * {@code MultiblockPattern} (rein lesende Musterprüfung) brauchbar.
 */
public final class ChestBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        Direction facing = Direction.fromYaw(ctx.playerYaw()).opposite();
        ChestType type = ChestType.SINGLE;
        Direction clicked = faceOf(ctx);

        /* Sneakend an die SEITE einer Truhe geklickt: verbindet TROTZ Sneak, und der Partner gibt
           die Ausrichtung vor (die eigene Blickrichtung zählt dann nicht). */
        if (clicked != null && clicked.axis() != Direction.Axis.Y && ctx.sneaking()) {
            Direction partner = partnerFacing(ctx, state, clicked.opposite());
            if (partner != null && partner.axis() != clicked.axis()) {
                facing = partner;
                type = partner.rotateYCCW() == clicked.opposite() ? ChestType.RIGHT : ChestType.LEFT;
            }
        }
        /* Sonst: automatisch mit einer gleich ausgerichteten Nachbartruhe verschmelzen — es sei
           denn, der Spieler sneakt. Das ist MCs bewusstes "nicht verbinden" und der einzige Weg,
           zwei Einzeltruhen nebeneinander zu stellen. */
        if (type == ChestType.SINGLE && !ctx.sneaking()) {
            if (facing == partnerFacing(ctx, state, facing.rotateYCW())) {
                type = ChestType.LEFT;
            } else if (facing == partnerFacing(ctx, state, facing.rotateYCCW())) {
                type = ChestType.RIGHT;
            }
        }
        return state.with(Properties.FACING, facing).with(Properties.CHEST_TYPE, type);
    }

    /**
     * Hält die Verbindung aktuell: Wegfall des Partners macht wieder eine Einzeltruhe, und eine
     * Einzeltruhe wird zum Gegenstück, sobald eine Nachbartruhe auf sie zeigt (so erfährt die
     * ZUERST gesetzte Truhe von der neu daneben platzierten).
     */
    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        ChestType type = state.get(Properties.CHEST_TYPE);
        Direction facing = state.get(Properties.FACING);

        if (type != ChestType.SINGLE) {
            Direction toPartner = ChestType.connectedDirection(facing, type);
            BlockState partner = chestAt(world, state, x + toPartner.offsetX(), y, z + toPartner.offsetZ());
            ChestType partnerType = partner == null ? null : partner.get(Properties.CHEST_TYPE);
            /* SINGLE zählt hier ausdrücklich als gültiger Partner — und das ist keine Lücke,
               sondern PFLICHT: Dimension.updateNeighbors aktualisiert als erstes den gerade gesetzten
               Block SELBST, und in diesem Moment steht der Partner noch auf SINGLE (er erfährt
               erst einen Schritt später von uns). Ohne diesen Fall würde sich jede frisch
               platzierte Hälfte sofort wieder zur Einzeltruhe zurücksetzen — MC hat das Problem
               nicht, weil dort updateShape nur auf den NACHBARN läuft, nie auf dem Block selbst. */
            boolean stillPaired = partner != null
                    && partner.get(Properties.FACING) == facing
                    && (partnerType == type.opposite() || partnerType == ChestType.SINGLE);
            return stillPaired ? state : state.with(Properties.CHEST_TYPE, ChestType.SINGLE);
        }

        for (Direction d : Direction.horizontalValues()) {
            BlockState neighbor = chestAt(world, state, x + d.offsetX(), y, z + d.offsetZ());
            if (neighbor == null || neighbor.get(Properties.FACING) != facing) continue;
            ChestType neighborType = neighbor.get(Properties.CHEST_TYPE);
            if (neighborType == ChestType.SINGLE) continue;
            /* Nur wenn der Nachbar auch wirklich MICH als Partner meint. */
            if (ChestType.connectedDirection(facing, neighborType) != d.opposite()) continue;
            return state.with(Properties.CHEST_TYPE, neighborType.opposite());
        }
        return state;
    }

    /** Beim Abbauen fällt der Inhalt heraus (sonst verschwindet er mit der BlockEntity). */
    @Override
    public void appendDrops(LootContext context,
                            LootSink sink) {
        Dimension world = context.world();
        BlockEntity be = world.getBlockEntity(context.x(), context.y(), context.z());
        if (!(be instanceof ChestBlockEntity chest)) return;
        ItemStorage inventory = chest.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) continue;
            inventory.set(slot, ItemStack.EMPTY);
            sink.accept(stack, context.x(), context.y(), context.z());
        }
    }

    /**
     * Facing der Nachbartruhe in Richtung {@code d}, oder null. Ein Nachbar, der bereits Teil
     * einer Doppeltruhe ist, zählt NICHT — sonst entstünden Dreierketten.
     */
    private static Direction partnerFacing(PlacementContext ctx, BlockState state, Direction d) {
        BlockState neighbor = chestAt(ctx.world(), state,
                ctx.x() + d.offsetX(), ctx.y() + d.offsetY(), ctx.z() + d.offsetZ());
        if (neighbor == null || neighbor.get(Properties.CHEST_TYPE) != ChestType.SINGLE) return null;
        return neighbor.get(Properties.FACING);
    }

    /** State an der Position, aber nur wenn es derselbe Truhen-Block ist. */
    private static BlockState chestAt(Dimension world, BlockState self, int x, int y, int z) {
        BlockState state = Blocks.getState(world.getBlock(x, y, z));
        return state.getBlock() == self.getBlock() ? state : null;
    }

    /** Getroffene Fläche als Richtung (der Kontext liefert sie als Offset-Vektor). */
    private static Direction faceOf(PlacementContext ctx) {
        for (Direction d : Direction.values()) {
            if (d.offsetX() == ctx.faceX() && d.offsetY() == ctx.faceY() && d.offsetZ() == ctx.faceZ()) {
                return d;
            }
        }
        return null;
    }
}
