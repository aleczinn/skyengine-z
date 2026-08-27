package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.PistonType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;

/**
 * Kolben-Kopf: existiert nur vor einer passenden ausgefahrenen Basis (oder deren laufender
 * Animation). Fehlt die, entfernt er sich selbst — dropfrei, er hat kein Item. Baut der
 * Spieler den KOPF ab, verschwindet die Basis mit; ihr Kolben-Item kommt über
 * {@link #appendDrops} aus dem Gamemode-geprüften Abbau-Pfad (Creative droppt nichts,
 * kein Doppel-Drop: setBlock ruft kein onBreak, und beim Selbstabbau passt die Basis nie).
 */
public final class PistonHeadBehavior implements BlockBehavior {

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        int bx = x - f.offsetX(), by = y - f.offsetY(), bz = z - f.offsetZ();
        BlockState base = Blocks.getState(world.getBlock(bx, by, bz));
        boolean supported = (base.getValues().containsKey(Properties.EXTENDED)
                && base.get(Properties.EXTENDED)
                && base.get(Properties.FACING_ALL) == f)
                || base.getBlock().getBlockEntityType() == BlockEntities.PISTON_MOVING;
        return supported ? state : Blocks.getState(Blocks.AIR);
    }

    @Override
    public void onBreak(Dimension world, int x, int y, int z, BlockState state) {
        if (!hasMatchingBase(world, x, y, z, state)) return;
        Direction f = state.get(Properties.FACING_ALL);
        world.setBlock(x - f.offsetX(), y - f.offsetY(), z - f.offsetZ(), Blocks.AIR, true);
    }

    @Override
    public void appendDrops(LootContext context,
                            LootSink sink) {
        BlockState state = context.state();
        if (!hasMatchingBase(context.world(), context.x(), context.y(), context.z(), state)) return;
        Item item = Items.get(Identifier.of(state.get(Properties.PISTON_TYPE) == PistonType.STICKY
                ? "sticky_piston" : "piston"));
        if (item != null) sink.accept(new ItemStack(item, 1), context.x(), context.y(), context.z());
    }

    /** Passende ausgefahrene Basis hinter dem Kopf? (Nur die wird beim Kopf-Abbau entfernt.) */
    private static boolean hasMatchingBase(Dimension world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        BlockState base = Blocks.getState(world.getBlock(x - f.offsetX(), y - f.offsetY(), z - f.offsetZ()));
        return base.getValues().containsKey(Properties.EXTENDED)
                && base.get(Properties.EXTENDED)
                && base.get(Properties.FACING_ALL) == f;
    }
}
