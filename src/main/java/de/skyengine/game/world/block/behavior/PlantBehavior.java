package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Verhalten für Cross-Pflanzen (Gras, Farn, Blumen): zerbricht, sobald die feste Stütze
 * darunter fehlt oder die Pflanze von einem Fluid erreicht wird. Kein Drop (vorerst).
 */
public final class PlantBehavior implements BlockBehavior {

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (canSurvive(world, x, y, z)) return state;
        return Blocks.getState(Blocks.AIR); // zerbricht
    }

    /** Überlebt nur auf einem festen Block und solange kein Fluid die Zelle erreicht. */
    private static boolean canSurvive(World world, int x, int y, int z) {
        BlockState below = Blocks.getState(world.getBlock(x, y - 1, z));
        if (!below.isSolid()) return false;            // Stütze weg / nicht fest
        return !isReachedByFluid(world, x, y, z);      // nicht überschwemmt
    }

    /** Fluid direkt darüber (fällt herein) oder horizontal angrenzend (fließt herein). */
    private static boolean isReachedByFluid(World world, int x, int y, int z) {
        if (Blocks.getState(world.getBlock(x, y + 1, z)).isFluid()) return true;
        for (Direction d : Direction.horizontal()) {
            if (Blocks.getState(world.getBlock(x + d.offsetX(), y, z + d.offsetZ())).isFluid()) return true;
        }
        return false;
    }
}
