package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
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

/**
 * Kolben-Kopf: existiert nur vor einer passenden ausgefahrenen Basis (oder deren laufender
 * Animation). Fehlt die, entfernt er sich selbst — dropfrei, er hat kein Item. Baut der
 * Spieler den KOPF ab, verschwindet die Basis mit und droppt ihr Kolben-Item (kein
 * Doppel-Drop: setBlock ruft kein onBreak, und beim Selbstabbau passt die Basis nie).
 */
public final class PistonHeadBehavior implements BlockBehavior {

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
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
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        int bx = x - f.offsetX(), by = y - f.offsetY(), bz = z - f.offsetZ();
        BlockState base = Blocks.getState(world.getBlock(bx, by, bz));
        boolean matching = base.getValues().containsKey(Properties.EXTENDED)
                && base.get(Properties.EXTENDED)
                && base.get(Properties.FACING_ALL) == f;
        if (!matching) return;

        world.setBlock(bx, by, bz, Blocks.AIR, true);
        Item item = Items.get(Identifier.of(state.get(Properties.PISTON_TYPE) == PistonType.STICKY
                ? "skyengine:sticky_piston" : "skyengine:piston"));
        if (item != null) world.spawnItem(bx + 0.5, by + 0.5, bz + 0.5, new ItemStack(item, 1));
    }
}
