package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;
import de.skyengine.game.world.loot.LootTables;

/**
 * Bewegter Block: träge — die ganze Logik lebt in der {@code PistonMovingBlockEntity}.
 * Nur der Abbau ist defensiv abgesichert (Härte −1 schützt vor dem Spieler, aber nicht
 * vor jedem künftigen Batch-Pfad): der transportierte Block droppt sein Item, statt still
 * zu verschwinden. Kopf-States besitzen eine explizit leere Loot-Tabelle.
 */
public final class MovingPistonBehavior implements BlockBehavior {

    @Override
    public void appendDrops(LootContext context,
                            LootSink sink) {
        Dimension world = context.world();
        if (!(world.getBlockEntity(context.x(), context.y(), context.z()) instanceof PistonMovingBlockEntity be)) return;
        BlockState moved = Blocks.getState(be.getMovedStateId());
        var movedContext = new LootContext(world,
                context.x(), context.y(), context.z(), moved, context.tool(), context.cause(),
                context.explosionRadius(), context.random());
        LootTables.generate(movedContext, sink);
    }
}
