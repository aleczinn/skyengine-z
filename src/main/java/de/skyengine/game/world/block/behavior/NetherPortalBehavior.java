package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.dimension.NetherPortalShape;
import de.skyengine.audio.BlockSoundGroup;

import java.util.Random;

/** Entfernt Portaloberflaechen, deren Obsidianrahmen nicht mehr vollstaendig ist. */
public final class NetherPortalBehavior implements BlockBehavior {

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (NetherPortalShape.isValidPortal(world, x, y, z, state.get(Properties.HORIZONTAL_AXIS))) {
            return state;
        }
        return Blocks.getState(Blocks.AIR);
    }

    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        NetherPortalShape.Collapse collapse = NetherPortalShape.collapse(
                world, x, y, z, state.get(Properties.HORIZONTAL_AXIS));
        if (collapse == null) return;
        if (world.getSoundManager() != null) {
            world.getSoundManager().playBreak(BlockSoundGroup.GLASS,
                    collapse.centerX(), collapse.centerY(), collapse.centerZ());
        }
        world.particles().portalCollapse(collapse.centerX(), collapse.centerY(), collapse.centerZ(),
                collapse.axis(), collapse.width(), collapse.height());
    }

    @Override
    public void animateTick(World world, int x, int y, int z, BlockState state, Random random) {
        if (random.nextInt(100) == 0 && world.getSoundManager() != null) {
            world.getSoundManager().playPortalAmbient(x + 0.5, y + 0.5, z + 0.5);
        }
        world.particles().portal(x, y, z, state.get(Properties.HORIZONTAL_AXIS), random);
    }
}
