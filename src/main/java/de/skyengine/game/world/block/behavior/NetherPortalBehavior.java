package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.dimension.NetherPortalShape;
import de.skyengine.game.world.dimension.PortalIndex;
import de.skyengine.audio.BlockSoundGroup;

import java.util.Random;

/** Entfernt Portaloberflaechen, deren Obsidianrahmen nicht mehr vollstaendig ist. */
public final class NetherPortalBehavior implements BlockBehavior {

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        if (NetherPortalShape.isValidPortal(world, x, y, z, state.get(Properties.HORIZONTAL_AXIS))) {
            return state;
        }
        return Blocks.getState(Blocks.AIR);
    }

    @Override
    public void onBreak(Dimension world, int x, int y, int z, BlockState state) {
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
    public void onRemoved(Dimension world, int x, int y, int z, BlockState oldState, BlockState newState) {
        Identifier type = Identifier.of("nether_portal");
        PortalIndex.Entry entry = world.getPortalIndex().containing(type, x, y, z);
        if (entry == null) return;
        NetherPortalShape.Shape intact = NetherPortalShape.find(world, x, y, z, false);
        if (intact != null && sameGeometry(entry, intact)) {
            world.getPortalIndex().deactivateContaining(type, x, y, z);
            return;
        }
        PortalIndex.Entry removed = world.getPortalIndex().removeContaining(type, x, y, z);
        if (removed != null) {
            world.getPortalLinks().unlink(type, world.getDimensionId(), removed.id());
        }
    }

    private static boolean sameGeometry(PortalIndex.Entry entry, NetherPortalShape.Shape shape) {
        return entry.x() == shape.minX() && entry.y() == shape.bottomY()
                && entry.z() == shape.minZ() && entry.portalAxis() == shape.axis()
                && entry.width() == shape.width() && entry.height() == shape.height();
    }

    @Override
    public void animateTick(Dimension world, int x, int y, int z, BlockState state, Random random) {
        if (random.nextInt(100) == 0 && world.getSoundManager() != null) {
            world.getSoundManager().playPortalAmbient(x + 0.5, y + 0.5, z + 0.5);
        }
        world.particles().portal(x, y, z, state.get(Properties.HORIZONTAL_AXIS), random);
    }
}
