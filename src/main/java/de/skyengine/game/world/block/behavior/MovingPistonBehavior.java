package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;

/**
 * Bewegter Block: träge — die ganze Logik lebt in der {@code PistonMovingBlockEntity}.
 * Nur der Abbau ist defensiv abgesichert (Härte −1 schützt vor dem Spieler, aber nicht
 * vor jedem künftigen Batch-Pfad): der transportierte Block droppt sein Item, statt still
 * zu verschwinden. Kopf-States droppen nichts (no_item → Items.forBlock liefert null).
 */
public final class MovingPistonBehavior implements BlockBehavior {

    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        if (!(world.getBlockEntity(x, y, z) instanceof PistonMovingBlockEntity be)) return;
        Item item = Items.forBlock(Blocks.getState(be.getMovedStateId()).getBlock());
        if (item != null) world.spawnItem(x + 0.5, y + 0.5, z + 0.5, new ItemStack(item, 1));
    }
}
